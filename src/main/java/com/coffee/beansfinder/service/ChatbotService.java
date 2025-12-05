package com.coffee.beansfinder.service;

import com.coffee.beansfinder.dto.*;
import com.coffee.beansfinder.entity.CoffeeProduct;
import com.coffee.beansfinder.graph.node.ProductNode;
import com.coffee.beansfinder.graph.repository.ProductNodeRepository;
import com.coffee.beansfinder.repository.CoffeeProductRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * LLM-Driven Chatbot Service (Grok as Brain, Neo4j Graph as RAG)
 *
 * IMPORTANT: This service is STATELESS - all conversation state is client-side (localStorage)
 *
 * Flow:
 * 1. User Query → Receive conversation history from client
 * 2. Fetch reference product + build GraphContext from Neo4j
 * 3. Call Grok with full context → Grok decides what graph query to run
 * 4. Execute Neo4j query based on Grok's decision
 * 5. Call Grok again to rank results + generate explanation
 * 6. Return recommendations + suggested next actions (client manages state)
 */
@Slf4j
@Service
public class ChatbotService {

    @Autowired
    private ProductNodeRepository productNodeRepository;

    @Autowired
    private CoffeeProductRepository coffeeProductRepository;

    @Autowired
    private GrokService grokService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ResourceLoader resourceLoader;

    @Value("${chatbot.max.context.products:15}")
    private int maxContextProducts;

    @Value("${chatbot.prompt.decision:classpath:config/grok-decision-prompt.txt}")
    private String decisionPromptPath;

    @Value("${chatbot.prompt.ranking:classpath:config/grok-ranking-prompt.txt}")
    private String rankingPromptPath;

    private String decisionPromptTemplate;
    private String rankingPromptTemplate;

    /**
     * Category index mapping for flavor profile vector queries.
     * Indices: 0=fruity, 1=floral, 2=sweet, 3=nutty, 4=spices, 5=roasted, 6=green, 7=sour, 8=other
     */
    private static final Map<String, Integer> FLAVOR_CATEGORY_INDEX_MAP = Map.of(
            "fruity", 0,
            "floral", 1,
            "sweet", 2,
            "nutty", 3,
            "spices", 4,
            "roasted", 5,
            "green", 6,
            "sour", 7,
            "other", 8
    );

    /**
     * Character axis index mapping for character axes vector queries.
     * Indices: 0=acidity, 1=body, 2=roast, 3=complexity
     */
    private static final Map<String, Integer> CHARACTER_AXIS_INDEX_MAP = Map.of(
            "acidity", 0,
            "body", 1,
            "roast", 2,
            "complexity", 3
    );

    /**
     * Load prompt templates on startup
     */
    @PostConstruct
    public void loadPromptTemplates() {
        try {
            Resource decisionResource = resourceLoader.getResource(decisionPromptPath);
            decisionPromptTemplate = new String(
                    decisionResource.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8
            );
            log.info("Loaded decision prompt template from: {}", decisionPromptPath);

            Resource rankingResource = resourceLoader.getResource(rankingPromptPath);
            rankingPromptTemplate = new String(
                    rankingResource.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8
            );
            log.info("Loaded ranking prompt template from: {}", rankingPromptPath);

        } catch (IOException e) {
            log.error("Failed to load prompt templates: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to load chatbot prompt templates", e);
        }
    }

    /**
     * Main chatbot query endpoint (stateless - all state is client-side)
     */
    public ChatbotResponse processQuery(ChatbotRequest request) {
        log.info("Processing chatbot query: {}", request.getQuery());

        try {
            // 1. Get conversation history from request (client-side)
            List<Map<String, Object>> messages = request.getMessages() != null
                    ? request.getMessages()
                    : new ArrayList<>();

            // 2. Get shown product IDs from request (client-side tracking)
            Set<Long> shownProductIds = request.getShownProductIds() != null
                    ? new HashSet<>(request.getShownProductIds())
                    : new HashSet<>();

            // 3. Get reference product
            ProductNode referenceProduct = null;
            if (request.getReferenceProductId() != null) {
                referenceProduct = productNodeRepository.findByProductId(request.getReferenceProductId()).orElse(null);
            }

            // 4. Build graph context if we have a reference product
            GraphContext graphContext = null;
            if (referenceProduct != null) {
                graphContext = buildGraphContext(referenceProduct);
            }

            // 5. Call Grok to decide what graph query to execute (Grok is the brain!)
            GrokDecision decision = callGrokForDecision(request.getQuery(), referenceProduct, graphContext, messages);

            // 6. Execute graph query based on Grok's decision
            List<ProductNode> candidateProducts = executeGraphQuery(decision, referenceProduct);

            // 7. Filter out already shown products (skip for SEARCH_BY_NAME - user explicitly asked)
            if (decision.getQueryType() != GrokDecision.GraphQueryType.SEARCH_BY_NAME) {
                candidateProducts = candidateProducts.stream()
                        .filter(p -> !shownProductIds.contains(p.getProductId()))
                        .collect(Collectors.toList());
            }

            // 8. Call Grok to rank and explain results
            List<ProductRecommendation> recommendations = callGrokForRanking(
                    candidateProducts,
                    request.getQuery(),
                    referenceProduct,
                    decision,
                    messages
            );

            // 9. Build response (client will manage state)
            String explanation = decision.getResponse();

            // If no products found, provide helpful message
            if (recommendations.isEmpty()) {
                if (candidateProducts.isEmpty()) {
                    explanation = "Sorry, I couldn't find any products matching your request. Try adjusting your filters (e.g., price range, origin, roast level) or explore different flavor profiles.";
                } else {
                    explanation = "All matching products have already been shown. Try exploring different characteristics or asking for something new!";
                }
            }

            ChatbotResponse response = ChatbotResponse.builder()
                    .products(recommendations)
                    .explanation(explanation)
                    .suggestedActions(convertSuggestedActions(decision.getSuggestedActions()))
                    .build();

            log.info("Chatbot response generated with {} recommendations", recommendations.size());
            return response;

        } catch (Exception e) {
            log.error("Error processing chatbot query: {}", e.getMessage(), e);
            return ChatbotResponse.builder()
                    .products(Collections.emptyList())
                    .explanation("Sorry, I encountered an error processing your request. Please try again.")
                    .build();
        }
    }


    /**
     * Build graph context statistics for RAG
     */
    private GraphContext buildGraphContext(ProductNode referenceProduct) {
        Long refId = referenceProduct.getProductId();

        // Get counts of available graph explorations
        long sameOriginCount = productNodeRepository.countBySameOrigin(refId);
        long sameRoastCount = productNodeRepository.countBySameRoast(refId);
        long sameProcessCount = productNodeRepository.countBySameProcess(refId);
        long similarFlavorCount = productNodeRepository.countBySimilarFlavors(refId);

        // Get available options
        List<String> availableOrigins = productNodeRepository.findAvailableOrigins();
        List<String> availableProcesses = productNodeRepository.findAvailableProcesses();

        // Build roast level list
        List<String> availableRoastLevels = Arrays.asList("Light", "Medium", "Dark", "Omni");

        // Build SCA category list
        List<String> scaCategories = Arrays.asList(
                "fruity", "floral", "sweet", "nutty", "roasted", "spicy", "sour", "vegetal", "other"
        );

        return GraphContext.builder()
                .sameOriginCount(sameOriginCount)
                .sameRoastCount(sameRoastCount)
                .sameProcessCount(sameProcessCount)
                .similarFlavorCount(similarFlavorCount)
                .availableOrigins(availableOrigins)
                .availableRoastLevels(availableRoastLevels)
                .availableProcesses(availableProcesses)
                .scaCategories(scaCategories)
                .build();
    }

    /**
     * Call Grok to decide what graph query to execute
     */
    private GrokDecision callGrokForDecision(
            String userQuery,
            ProductNode referenceProduct,
            GraphContext graphContext,
            List<Map<String, Object>> conversationHistory) throws Exception {

        // Build system prompt
        String systemPrompt = buildDecisionSystemPrompt(referenceProduct, graphContext);

        // Call Grok with conversation history
        String grokResponse = grokService.callGrokWithHistory(
                systemPrompt,
                conversationHistory,
                userQuery
        );

        log.info("Grok decision response: {}", grokResponse);

        // Parse JSON response into GrokDecision
        GrokDecision decision = objectMapper.readValue(grokResponse, GrokDecision.class);

        return decision;
    }

    /**
     * Build system prompt for Grok decision-making (using template from config file)
     */
    private String buildDecisionSystemPrompt(ProductNode referenceProduct, GraphContext graphContext) {
        String prompt = decisionPromptTemplate;

        // Replace {{REFERENCE_PRODUCT}} placeholder
        if (referenceProduct != null) {
            StringBuilder refProduct = new StringBuilder();
            refProduct.append("=== REFERENCE PRODUCT ===\n");
            refProduct.append(String.format("Name: %s\n", referenceProduct.getProductName()));
            refProduct.append(String.format("Brand: %s\n", referenceProduct.getSoldBy() != null ? referenceProduct.getSoldBy().getName() : "Unknown"));
            if (referenceProduct.getOrigins() != null && !referenceProduct.getOrigins().isEmpty()) {
                String origins = referenceProduct.getOrigins().stream()
                        .map(o -> o.getCountry())
                        .collect(Collectors.joining(", "));
                refProduct.append(String.format("Origin: %s\n", origins));
            }
            refProduct.append(String.format("Roast: %s\n", referenceProduct.getRoastLevel() != null ? referenceProduct.getRoastLevel().getLevel() : "Unknown"));
            if (referenceProduct.getProcesses() != null && !referenceProduct.getProcesses().isEmpty()) {
                String processes = referenceProduct.getProcesses().stream()
                        .map(p -> p.getType())
                        .collect(Collectors.joining(", "));
                refProduct.append(String.format("Process: %s\n", processes));
            }
            refProduct.append(String.format("Price: £%.2f\n", referenceProduct.getPrice()));

            if (referenceProduct.getTastingNotes() != null && !referenceProduct.getTastingNotes().isEmpty()) {
                String flavors = referenceProduct.getTastingNotes().stream()
                        .map(f -> f.getName() + " (" + f.getScaCategory() + ")")
                        .collect(Collectors.joining(", "));
                refProduct.append(String.format("Flavors: %s\n", flavors));
            }
            refProduct.append("\n");

            prompt = prompt.replace("{{REFERENCE_PRODUCT}}", refProduct.toString());
        } else {
            prompt = prompt.replace("{{REFERENCE_PRODUCT}}", "");
        }

        // Replace {{GRAPH_CONTEXT}} placeholder
        if (graphContext != null) {
            StringBuilder graphCtx = new StringBuilder();
            graphCtx.append("=== NEO4J GRAPH CONTEXT ===\n");
            graphCtx.append(String.format("Products from same origin: %d\n", graphContext.getSameOriginCount()));
            graphCtx.append(String.format("Products with same roast level: %d\n", graphContext.getSameRoastCount()));
            graphCtx.append(String.format("Products with same process: %d\n", graphContext.getSameProcessCount()));
            graphCtx.append(String.format("Products with similar flavors: %d\n", graphContext.getSimilarFlavorCount()));
            graphCtx.append(String.format("Available origins: %s\n", String.join(", ", graphContext.getAvailableOrigins().subList(0, Math.min(10, graphContext.getAvailableOrigins().size())))));
            graphCtx.append(String.format("Available processes: %s\n", String.join(", ", graphContext.getAvailableProcesses())));
            graphCtx.append("\n");

            prompt = prompt.replace("{{GRAPH_CONTEXT}}", graphCtx.toString());
        } else {
            prompt = prompt.replace("{{GRAPH_CONTEXT}}", "");
        }

        return prompt;
    }

    /**
     * Execute Neo4j graph query based on Grok's decision
     */
    private List<ProductNode> executeGraphQuery(GrokDecision decision, ProductNode referenceProduct) {
        if (decision.getQueryType() == null) {
            log.warn("No query type specified in Grok decision, returning empty list");
            return Collections.emptyList();
        }

        Long refId = referenceProduct != null ? referenceProduct.getProductId() : null;
        GrokDecision.QueryFilters filters = decision.getFilters();

        log.info("Executing graph query: {} with filters: {}", decision.getQueryType(), filters);

        List<ProductNode> results;

        switch (decision.getQueryType()) {
            case SEARCH_BY_NAME:
                if (filters != null && filters.getProductName() != null) {
                    log.info("Searching for product by name: {}", filters.getProductName());
                    results = productNodeRepository.findByProductNameContaining(filters.getProductName());
                    log.info("Found {} products matching '{}'", results.size(), filters.getProductName());
                } else {
                    log.warn("SEARCH_BY_NAME requested but no productName in filters");
                    results = Collections.emptyList();
                }
                break;

            case SEARCH_BY_BRAND:
                if (filters != null && filters.getBrandName() != null) {
                    log.info("Searching for products by brand: {}", filters.getBrandName());
                    results = productNodeRepository.findByBrandName(filters.getBrandName());
                    log.info("Found {} products from brand '{}'", results.size(), filters.getBrandName());
                } else {
                    log.warn("SEARCH_BY_BRAND requested but no brandName in filters");
                    results = Collections.emptyList();
                }
                break;

            case SIMILAR_FLAVORS:
                Long effectiveRefId = refId;

                // If no refId, try to infer from product name
                if (effectiveRefId == null && filters != null && filters.getProductName() != null) {
                    log.info("SIMILAR_FLAVORS without refId, searching by product name: {}", filters.getProductName());
                    List<ProductNode> foundProducts = productNodeRepository.findByProductNameContaining(filters.getProductName());
                    if (!foundProducts.isEmpty()) {
                        effectiveRefId = foundProducts.get(0).getProductId();
                        log.info("Using inferred reference product ID: {}", effectiveRefId);
                    }
                }

                if (effectiveRefId != null) {
                    // Tiered fallback: TastingNote -> Attribute -> Subcategory
                    results = productNodeRepository.findSimilarByFlavorOverlap(effectiveRefId, maxContextProducts);
                    log.info("Level 1 (TastingNote): found {} products", results.size());

                    if (results.isEmpty()) {
                        results = productNodeRepository.findSimilarByAttributeOverlap(effectiveRefId, maxContextProducts);
                        log.info("Level 2 (Attribute): found {} products", results.size());
                    }

                    if (results.isEmpty()) {
                        results = productNodeRepository.findSimilarBySubcategoryOverlap(effectiveRefId, maxContextProducts);
                        log.info("Level 3 (Subcategory): found {} products", results.size());
                    }
                } else {
                    log.warn("SIMILAR_FLAVORS requires either refId or productName in filters");
                    results = Collections.emptyList();
                }
                break;

            case SAME_ORIGIN:
                if (filters != null && filters.getOrigin() != null) {
                    results = productNodeRepository.findByOriginCountry(filters.getOrigin());
                } else if (refId != null) {
                    ProductNode ref = productNodeRepository.findByProductId(refId).orElse(null);
                    if (ref != null && ref.getOrigins() != null && !ref.getOrigins().isEmpty()) {
                        String originCountry = ref.getOrigins().stream().findFirst().get().getCountry();
                        results = productNodeRepository.findByOriginCountry(originCountry);
                    } else {
                        results = Collections.emptyList();
                    }
                } else {
                    results = Collections.emptyList();
                }
                break;

            case SAME_ROAST:
                if (filters != null && filters.getRoastLevel() != null) {
                    results = productNodeRepository.findByRoastLevel(filters.getRoastLevel());
                } else {
                    results = Collections.emptyList();
                }
                break;

            case SAME_PROCESS:
                if (filters != null && filters.getProcess() != null) {
                    results = productNodeRepository.findByProcessType(filters.getProcess());
                } else {
                    results = Collections.emptyList();
                }
                break;

            case MORE_CATEGORY:
                if (refId != null && filters != null && filters.getScaCategory() != null) {
                    String category = filters.getScaCategory().toLowerCase();
                    Integer categoryIndex = FLAVOR_CATEGORY_INDEX_MAP.get(category);

                    // Try profile-based vector query first (more accurate intensity comparison)
                    if (categoryIndex != null) {
                        results = productNodeRepository.findByMoreCategoryProfile(refId, categoryIndex, maxContextProducts);
                        log.info("MORE_CATEGORY with profile vector (index {}): found {} products", categoryIndex, results.size());
                    } else {
                        results = Collections.emptyList();
                    }

                    // Fallback to flavor hierarchy query if no profile results
                    if (results.isEmpty()) {
                        log.info("No profile results, falling back to flavor hierarchy query");
                        results = productNodeRepository.findByMoreCategoryWithFlavorOverlap(refId, filters.getScaCategory(), maxContextProducts);
                        log.info("MORE_CATEGORY with flavor overlap: found {} products", results.size());
                    }

                    // Final fallback to standard query if still no results
                    if (results.isEmpty()) {
                        log.info("No flavor overlap results, falling back to standard MORE_CATEGORY query");
                        results = productNodeRepository.findByMoreCategory(refId, filters.getScaCategory(), maxContextProducts);
                    }
                } else if (filters != null && filters.getScaCategory() != null) {
                    // FALLBACK: No reference product, search all products with this SCA category
                    log.info("MORE_CATEGORY without refId, searching all products with scaCategory: {}", filters.getScaCategory());
                    results = productNodeRepository.findBySCACategory(filters.getScaCategory());
                    results = results.stream().limit(maxContextProducts).collect(Collectors.toList());
                } else {
                    results = Collections.emptyList();
                }
                break;

            case SAME_ORIGIN_MORE_CATEGORY:
                if (refId != null && filters != null && filters.getScaCategory() != null) {
                    results = productNodeRepository.findBySameOriginMoreCategory(refId, filters.getScaCategory(), maxContextProducts);
                } else if (filters != null && filters.getOrigin() != null && filters.getScaCategory() != null) {
                    // FALLBACK: No reference, but has origin + category filters
                    log.info("SAME_ORIGIN_MORE_CATEGORY without refId, using filters: origin={}, category={}",
                            filters.getOrigin(), filters.getScaCategory());
                    List<ProductNode> originProducts = productNodeRepository.findByOriginCountry(filters.getOrigin());
                    results = productNodeRepository.findBySCACategory(filters.getScaCategory());
                    // Intersection: products matching both origin AND category
                    results = results.stream()
                            .filter(originProducts::contains)
                            .limit(maxContextProducts)
                            .collect(Collectors.toList());
                } else {
                    results = Collections.emptyList();
                }
                break;

            case SAME_ORIGIN_DIFFERENT_ROAST:
                if (refId != null && filters != null && filters.getRoastLevel() != null) {
                    results = productNodeRepository.findBySameOriginDifferentRoast(refId, filters.getRoastLevel(), maxContextProducts);
                } else if (filters != null && filters.getOrigin() != null && filters.getRoastLevel() != null) {
                    // FALLBACK: No reference, but has origin + roast filters
                    log.info("SAME_ORIGIN_DIFFERENT_ROAST without refId, using filters: origin={}, roast={}",
                            filters.getOrigin(), filters.getRoastLevel());
                    List<ProductNode> originProducts = productNodeRepository.findByOriginCountry(filters.getOrigin());
                    List<ProductNode> roastProducts = productNodeRepository.findByRoastLevel(filters.getRoastLevel());
                    // Intersection: products matching both origin AND roast
                    results = originProducts.stream()
                            .filter(roastProducts::contains)
                            .limit(maxContextProducts)
                            .collect(Collectors.toList());
                } else {
                    results = Collections.emptyList();
                }
                break;

            // ==================== CHARACTER AXES QUERIES ====================

            case MORE_CHARACTER:
                if (refId != null && filters != null && filters.getCharacterAxis() != null) {
                    String axis = filters.getCharacterAxis().toLowerCase();
                    Integer axisIndex = CHARACTER_AXIS_INDEX_MAP.get(axis);

                    if (axisIndex != null) {
                        // HYBRID APPROACH: Try flavor overlap + axis filter FIRST (preserves original tasting notes)
                        results = productNodeRepository.findByMoreCharacterAxisWithFlavorOverlap(refId, axisIndex, maxContextProducts);
                        log.info("MORE_CHARACTER with flavor overlap (axis={}, index={}): found {} products", axis, axisIndex, results.size());

                        // FALLBACK to vector-only if no flavor overlap matches
                        if (results.isEmpty()) {
                            log.info("No flavor overlap matches, falling back to vector-only query");
                            results = productNodeRepository.findByMoreCharacterAxis(refId, axisIndex, maxContextProducts);
                            log.info("MORE_CHARACTER with vector-only: found {} products", results.size());
                        }
                    } else {
                        results = Collections.emptyList();
                    }

                    // Final fallback: Use related SCA category or roast level queries
                    if (results.isEmpty()) {
                        log.info("No vector results for MORE_CHARACTER, falling back to related queries");
                        results = fallbackMoreCharacter(refId, axis);
                    }
                } else if (filters != null && filters.getCharacterAxis() != null) {
                    // No reference product - use general fallback
                    log.info("MORE_CHARACTER without refId, using general fallback for axis: {}", filters.getCharacterAxis());
                    results = fallbackMoreCharacterNoRef(filters.getCharacterAxis().toLowerCase());
                } else {
                    results = Collections.emptyList();
                }
                break;

            case LESS_CHARACTER:
                if (refId != null && filters != null && filters.getCharacterAxis() != null) {
                    String axis = filters.getCharacterAxis().toLowerCase();
                    Integer axisIndex = CHARACTER_AXIS_INDEX_MAP.get(axis);

                    if (axisIndex != null) {
                        // HYBRID APPROACH: Try flavor overlap + axis filter FIRST (preserves original tasting notes)
                        results = productNodeRepository.findByLessCharacterAxisWithFlavorOverlap(refId, axisIndex, maxContextProducts);
                        log.info("LESS_CHARACTER with flavor overlap (axis={}, index={}): found {} products", axis, axisIndex, results.size());

                        // FALLBACK to vector-only if no flavor overlap matches
                        if (results.isEmpty()) {
                            log.info("No flavor overlap matches, falling back to vector-only query");
                            results = productNodeRepository.findByLessCharacterAxis(refId, axisIndex, maxContextProducts);
                            log.info("LESS_CHARACTER with vector-only: found {} products", results.size());
                        }
                    } else {
                        results = Collections.emptyList();
                    }

                    // Final fallback: Use related SCA category or roast level queries
                    if (results.isEmpty()) {
                        log.info("No vector results for LESS_CHARACTER, falling back to related queries");
                        results = fallbackLessCharacter(refId, axis);
                    }
                } else if (filters != null && filters.getCharacterAxis() != null) {
                    // No reference product - use general fallback
                    log.info("LESS_CHARACTER without refId, using general fallback for axis: {}", filters.getCharacterAxis());
                    results = fallbackLessCharacterNoRef(filters.getCharacterAxis().toLowerCase());
                } else {
                    results = Collections.emptyList();
                }
                break;

            case SIMILAR_PROFILE:
                if (refId != null) {
                    // Try vector-based cosine similarity
                    results = productNodeRepository.findBySimilarProfile(refId, maxContextProducts);
                    log.info("SIMILAR_PROFILE with cosine similarity: found {} products", results.size());

                    // Fallback: Use flavor overlap query
                    if (results.isEmpty()) {
                        log.info("No vector results for SIMILAR_PROFILE, falling back to flavor overlap");
                        results = productNodeRepository.findSimilarByFlavorOverlap(refId, maxContextProducts);
                    }
                } else {
                    results = Collections.emptyList();
                }
                break;

            case CUSTOM:
                // Handle custom queries with multiple filters
                results = executeCustomQuery(filters);
                break;

            default:
                log.warn("Unknown query type: {}", decision.getQueryType());
                results = Collections.emptyList();
        }

        // Apply price filters if specified
        if (filters != null && !results.isEmpty()) {
            if (filters.getMaxPrice() != null) {
                results = results.stream()
                        .filter(p -> p.getPrice() != null && p.getPrice().compareTo(filters.getMaxPrice()) <= 0)
                        .collect(Collectors.toList());
            }
            if (filters.getMinPrice() != null) {
                results = results.stream()
                        .filter(p -> p.getPrice() != null && p.getPrice().compareTo(filters.getMinPrice()) >= 0)
                        .collect(Collectors.toList());
            }
        }

        // Exclude reference product (except for SEARCH_BY_NAME where user explicitly asks about it)
        if (refId != null && decision.getQueryType() != GrokDecision.GraphQueryType.SEARCH_BY_NAME) {
            results = results.stream()
                    .filter(p -> !p.getProductId().equals(refId))
                    .collect(Collectors.toList());
        }

        log.info("Graph query returned {} products", results.size());
        return results;
    }

    /**
     * Execute custom query with multiple filters
     */
    private List<ProductNode> executeCustomQuery(GrokDecision.QueryFilters filters) {
        // Start with all products, then apply filters
        // This is a simplified implementation - you could optimize with a custom Cypher query

        List<ProductNode> results = new ArrayList<>();

        if (filters.getOrigin() != null) {
            results.addAll(productNodeRepository.findByOriginCountry(filters.getOrigin()));
        }

        if (filters.getProcess() != null) {
            List<ProductNode> byProcess = productNodeRepository.findByProcessType(filters.getProcess());
            if (results.isEmpty()) {
                results.addAll(byProcess);
            } else {
                // Intersection
                results = results.stream()
                        .filter(byProcess::contains)
                        .collect(Collectors.toList());
            }
        }

        if (filters.getRoastLevel() != null) {
            List<ProductNode> byRoast = productNodeRepository.findByRoastLevel(filters.getRoastLevel());
            if (results.isEmpty()) {
                results.addAll(byRoast);
            } else {
                results = results.stream()
                        .filter(byRoast::contains)
                        .collect(Collectors.toList());
            }
        }

        return results.stream().limit(maxContextProducts).collect(Collectors.toList());
    }

    /**
     * Call Grok to rank results and generate explanations
     */
    private List<ProductRecommendation> callGrokForRanking(
            List<ProductNode> candidates,
            String userQuery,
            ProductNode referenceProduct,
            GrokDecision decision,
            List<Map<String, Object>> conversationHistory) throws Exception {

        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }

        // Limit candidates to top 15 to save tokens
        List<ProductNode> topCandidates = candidates.stream()
                .limit(maxContextProducts)
                .collect(Collectors.toList());

        String systemPrompt = buildRankingSystemPrompt(topCandidates, referenceProduct, userQuery);

        String grokResponse = grokService.callGrokWithHistory(
                systemPrompt,
                conversationHistory,
                "Rank these products and explain why each matches my request."
        );

        log.info("Grok ranking response: {}", grokResponse);

        // Parse JSON response
        Map<String, Object> rankingResult = objectMapper.readValue(grokResponse, new TypeReference<Map<String, Object>>() {});
        List<Map<String, Object>> rankedProducts = (List<Map<String, Object>>) rankingResult.get("products");

        // Convert to ProductRecommendation objects
        List<ProductRecommendation> recommendations = new ArrayList<>();
        for (Map<String, Object> item : rankedProducts) {
            Long productId = ((Number) item.get("productId")).longValue();
            String reason = (String) item.get("reason");

            // Find the ProductNode
            Optional<ProductNode> productOpt = topCandidates.stream()
                    .filter(p -> p.getProductId().equals(productId))
                    .findFirst();

            if (productOpt.isPresent()) {
                ProductNode product = productOpt.get();

                String origin = "Unknown";
                if (product.getOrigins() != null && !product.getOrigins().isEmpty()) {
                    // Format origin as "Region, Country" or just "Country" if no region
                    // Deduplicate to avoid showing "Colombia, Colombia"
                    origin = product.getOrigins().stream()
                            .map(o -> {
                                if (o.getRegion() != null && !o.getRegion().trim().isEmpty()) {
                                    return o.getRegion() + ", " + o.getCountry();
                                } else {
                                    return o.getCountry();
                                }
                            })
                            .distinct() // Remove duplicates
                            .collect(Collectors.joining(" / ")); // Use " / " for multiple origins
                }

                List<String> flavors = new ArrayList<>();
                if (product.getTastingNotes() != null && !product.getTastingNotes().isEmpty()) {
                    flavors = product.getTastingNotes().stream()
                            .map(f -> f.getName())
                            .collect(Collectors.toList());
                    log.info("Product {} has {} tasting notes: {}", productId, flavors.size(), flavors);
                } else {
                    log.info("Product {} has NO tasting notes loaded from Neo4j (tastingNotes={})",
                            productId, product.getTastingNotes());
                }

                // Fetch price variants from PostgreSQL
                List<ProductRecommendation.PriceVariant> priceVariants = null;
                try {
                    Optional<CoffeeProduct> sqlProduct = coffeeProductRepository.findById(product.getProductId());
                    if (sqlProduct.isPresent() && sqlProduct.get().getPriceVariantsJson() != null) {
                        List<Map<String, Object>> variantsData = objectMapper.readValue(
                                sqlProduct.get().getPriceVariantsJson(),
                                new TypeReference<List<Map<String, Object>>>() {});
                        priceVariants = variantsData.stream()
                                .map(v -> ProductRecommendation.PriceVariant.builder()
                                        .size((String) v.get("size"))
                                        .price(new BigDecimal(v.get("price").toString()))
                                        .build())
                                .collect(Collectors.toList());
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse price variants for product {}: {}", productId, e.getMessage());
                }

                recommendations.add(ProductRecommendation.builder()
                        .id(product.getProductId())
                        .name(product.getProductName())
                        .brand(product.getSoldBy() != null ? product.getSoldBy().getName() : "Unknown")
                        .origin(origin)
                        .roastLevel(product.getRoastLevel() != null ? product.getRoastLevel().getLevel() : "Unknown")
                        .price(product.getPrice())
                        .priceVariants(priceVariants)
                        .currency(product.getCurrency() != null ? product.getCurrency() : "GBP")
                        .flavors(flavors)
                        .url(product.getSellerUrl())
                        .reason(reason)
                        .build());
            }
        }

        return recommendations;
    }

    /**
     * Build system prompt for Grok ranking (using template from config file)
     */
    private String buildRankingSystemPrompt(List<ProductNode> candidates, ProductNode referenceProduct, String userQuery) {
        String prompt = rankingPromptTemplate;

        // Replace {{USER_QUERY}} placeholder
        prompt = prompt.replace("{{USER_QUERY}}", userQuery);

        // Replace {{REFERENCE_PRODUCT}} placeholder
        if (referenceProduct != null) {
            String refProduct = "=== REFERENCE PRODUCT ===\n" +
                    formatProductDetails(referenceProduct) + "\n";
            prompt = prompt.replace("{{REFERENCE_PRODUCT}}", refProduct);
        } else {
            prompt = prompt.replace("{{REFERENCE_PRODUCT}}", "");
        }

        // Replace {{CANDIDATE_PRODUCTS}} placeholder
        StringBuilder candidatesList = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            candidatesList.append(String.format("%d. ", i + 1));
            candidatesList.append(formatProductDetails(candidates.get(i)));
            candidatesList.append("\n");
        }
        prompt = prompt.replace("{{CANDIDATE_PRODUCTS}}", candidatesList.toString());

        return prompt;
    }

    /**
     * Format product details for prompts
     */
    private String formatProductDetails(ProductNode product) {
        String flavors = "No flavors listed";
        if (product.getTastingNotes() != null && !product.getTastingNotes().isEmpty()) {
            flavors = product.getTastingNotes().stream()
                    .map(f -> f.getName() + " (" + f.getScaCategory() + ")")
                    .collect(Collectors.joining(", "));
        }

        String origins = "Unknown";
        if (product.getOrigins() != null && !product.getOrigins().isEmpty()) {
            origins = product.getOrigins().stream()
                    .map(o -> o.getCountry())
                    .collect(Collectors.joining(", "));
        }

        String process = "Unknown";
        if (product.getProcesses() != null && !product.getProcesses().isEmpty()) {
            process = product.getProcesses().stream()
                    .map(p -> p.getType())
                    .collect(Collectors.joining(", "));
        }

        return String.format(
                "ID: %d | Name: %s | Brand: %s | Origin: %s | Roast: %s | Process: %s | Price: £%.2f | Flavors: %s",
                product.getProductId(),
                product.getProductName(),
                product.getSoldBy() != null ? product.getSoldBy().getName() : "Unknown",
                origins,
                product.getRoastLevel() != null ? product.getRoastLevel().getLevel() : "Unknown",
                process,
                product.getPrice(),
                flavors
        );
    }

    /**
     * Convert GrokDecision.SuggestedAction to ChatbotResponse format
     */
    private List<Map<String, String>> convertSuggestedActions(List<GrokDecision.SuggestedAction> actions) {
        if (actions == null) {
            return Collections.emptyList();
        }

        return actions.stream()
                .map(action -> {
                    Map<String, String> map = new HashMap<>();
                    map.put("label", action.getLabel());
                    map.put("intent", action.getIntent());
                    if (action.getIcon() != null) {
                        map.put("icon", action.getIcon());
                    }
                    return map;
                })
                .collect(Collectors.toList());
    }

    // ==================== CHARACTER AXES FALLBACK METHODS ====================

    /**
     * Fallback for MORE_CHARACTER when vector query returns no results.
     * Maps character axes to related SCA categories or attributes.
     */
    private List<ProductNode> fallbackMoreCharacter(Long refId, String axis) {
        switch (axis) {
            case "acidity":
                // More acidity → Ethiopian/Kenyan origins or "sour" SCA category (bright, tangy)
                log.info("Fallback: MORE acidity → searching sour/bright flavors");
                List<ProductNode> sourProducts = productNodeRepository.findBySCACategory("sour");
                if (sourProducts.isEmpty()) {
                    // Try Ethiopian/Kenyan origins known for bright acidity
                    sourProducts = productNodeRepository.findByOriginCountry("Ethiopia");
                    sourProducts.addAll(productNodeRepository.findByOriginCountry("Kenya"));
                }
                return sourProducts.stream().limit(maxContextProducts).collect(Collectors.toList());

            case "body":
                // More body → Natural process or Indonesian/Brazilian origins
                log.info("Fallback: MORE body → searching natural process or full-bodied origins");
                List<ProductNode> bodyProducts = productNodeRepository.findByProcessType("Natural");
                if (bodyProducts.isEmpty()) {
                    bodyProducts = productNodeRepository.findByOriginCountry("Brazil");
                    bodyProducts.addAll(productNodeRepository.findByOriginCountry("Indonesia"));
                }
                return bodyProducts.stream().limit(maxContextProducts).collect(Collectors.toList());

            case "roast":
                // More roast → Dark roast level or "roasted" SCA category
                log.info("Fallback: MORE roast → searching dark roast or roasted flavors");
                List<ProductNode> roastProducts = productNodeRepository.findByRoastLevel("Dark");
                if (roastProducts.isEmpty()) {
                    roastProducts = productNodeRepository.findBySCACategory("roasted");
                }
                return roastProducts.stream().limit(maxContextProducts).collect(Collectors.toList());

            case "complexity":
                // More complexity → Natural/Anaerobic process or "other" SCA category (funky, winey)
                log.info("Fallback: MORE complexity → searching natural/anaerobic or funky flavors");
                List<ProductNode> complexProducts = productNodeRepository.findByProcessType("Natural");
                if (complexProducts.isEmpty()) {
                    complexProducts = productNodeRepository.findBySCACategory("other");
                }
                return complexProducts.stream().limit(maxContextProducts).collect(Collectors.toList());

            default:
                return Collections.emptyList();
        }
    }

    /**
     * Fallback for LESS_CHARACTER when vector query returns no results.
     */
    private List<ProductNode> fallbackLessCharacter(Long refId, String axis) {
        switch (axis) {
            case "acidity":
                // Less acidity → Brazilian/Indonesian origins or washed process
                log.info("Fallback: LESS acidity → searching smooth/mellow origins");
                List<ProductNode> smoothProducts = productNodeRepository.findByOriginCountry("Brazil");
                smoothProducts.addAll(productNodeRepository.findByOriginCountry("Indonesia"));
                if (smoothProducts.isEmpty()) {
                    smoothProducts = productNodeRepository.findByProcessType("Washed");
                }
                return smoothProducts.stream().limit(maxContextProducts).collect(Collectors.toList());

            case "body":
                // Less body → Light roast or washed process
                log.info("Fallback: LESS body → searching light roast or washed");
                List<ProductNode> lightProducts = productNodeRepository.findByRoastLevel("Light");
                if (lightProducts.isEmpty()) {
                    lightProducts = productNodeRepository.findByProcessType("Washed");
                }
                return lightProducts.stream().limit(maxContextProducts).collect(Collectors.toList());

            case "roast":
                // Less roast → Light roast level
                log.info("Fallback: LESS roast → searching light roast");
                return productNodeRepository.findByRoastLevel("Light").stream()
                        .limit(maxContextProducts).collect(Collectors.toList());

            case "complexity":
                // Less complexity → Washed process (cleaner cup)
                log.info("Fallback: LESS complexity → searching washed/clean cup");
                return productNodeRepository.findByProcessType("Washed").stream()
                        .limit(maxContextProducts).collect(Collectors.toList());

            default:
                return Collections.emptyList();
        }
    }

    /**
     * Fallback for MORE_CHARACTER without reference product.
     */
    private List<ProductNode> fallbackMoreCharacterNoRef(String axis) {
        switch (axis) {
            case "acidity":
                log.info("No-ref fallback: MORE acidity → Ethiopian/Kenyan coffees");
                List<ProductNode> results = productNodeRepository.findByOriginCountry("Ethiopia");
                results.addAll(productNodeRepository.findByOriginCountry("Kenya"));
                return results.stream().limit(maxContextProducts).collect(Collectors.toList());

            case "body":
                log.info("No-ref fallback: MORE body → Natural process");
                return productNodeRepository.findByProcessType("Natural").stream()
                        .limit(maxContextProducts).collect(Collectors.toList());

            case "roast":
                log.info("No-ref fallback: MORE roast → Dark roast");
                return productNodeRepository.findByRoastLevel("Dark").stream()
                        .limit(maxContextProducts).collect(Collectors.toList());

            case "complexity":
                log.info("No-ref fallback: MORE complexity → Natural process");
                return productNodeRepository.findByProcessType("Natural").stream()
                        .limit(maxContextProducts).collect(Collectors.toList());

            default:
                return Collections.emptyList();
        }
    }

    /**
     * Fallback for LESS_CHARACTER without reference product.
     */
    private List<ProductNode> fallbackLessCharacterNoRef(String axis) {
        switch (axis) {
            case "acidity":
                log.info("No-ref fallback: LESS acidity → Brazilian coffees");
                return productNodeRepository.findByOriginCountry("Brazil").stream()
                        .limit(maxContextProducts).collect(Collectors.toList());

            case "body":
                log.info("No-ref fallback: LESS body → Light roast");
                return productNodeRepository.findByRoastLevel("Light").stream()
                        .limit(maxContextProducts).collect(Collectors.toList());

            case "roast":
                log.info("No-ref fallback: LESS roast → Light roast");
                return productNodeRepository.findByRoastLevel("Light").stream()
                        .limit(maxContextProducts).collect(Collectors.toList());

            case "complexity":
                log.info("No-ref fallback: LESS complexity → Washed process");
                return productNodeRepository.findByProcessType("Washed").stream()
                        .limit(maxContextProducts).collect(Collectors.toList());

            default:
                return Collections.emptyList();
        }
    }
}
