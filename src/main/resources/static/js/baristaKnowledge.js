// ============================================
// BARISTA KNOWLEDGE BASE
// Runtime calculation - No LLM needed
// ============================================

// -----------------------------------------
// ORIGIN PATTERNS
// Key: origin name (lowercase, partial match)
// -----------------------------------------
const ORIGIN_PATTERNS = {

  // === AFRICA ===

  ethiopia: {
    washed: {
      pattern: 'Classic washed Ethiopian',
      character: 'tea-like clarity, floral and citrus',
      expect: 'Delicate, bright, almost like drinking flavored tea',
      polarization: 'crowd_pleaser'
    },
    natural: {
      pattern: 'Ethiopian "blueberry bomb"',
      character: 'heavy fruit, jammy, wine-like ferment',
      expect: 'Intense berry sweetness, some funky ferment notes',
      polarization: 'love_or_hate'
    },
    honey: {
      pattern: 'Honey-processed Ethiopian',
      character: 'fruit-forward with balanced sweetness',
      expect: 'Sweeter than washed, cleaner than natural',
      polarization: 'generally_liked'
    },
    anaerobic: {
      pattern: 'Experimental Ethiopian',
      character: 'intense fruit, candy-like, possibly boozy',
      expect: 'Wild, unexpected flavors from controlled fermentation',
      polarization: 'adventurous_only'
    },
    default: {
      pattern: 'Ethiopian origin',
      character: 'bright, fruity, complex - the birthplace of coffee',
      polarization: 'generally_liked'
    }
  },

  kenya: {
    default: {
      pattern: 'Kenyan brightness',
      character: 'intense acidity, blackcurrant, tomato-like savory notes',
      expect: 'Bold, punchy, unmistakably "Kenyan" character',
      polarization: 'love_or_hate'
    }
  },

  rwanda: {
    washed: {
      pattern: 'Clean Rwandan',
      character: 'bright, citrus, tea-like clarity',
      expect: 'Elegant and refined, similar to washed Ethiopian',
      polarization: 'crowd_pleaser'
    },
    natural: {
      pattern: 'Rwandan natural',
      character: 'fruity sweetness with clean finish',
      expect: 'Fruit-forward but more restrained than Ethiopian naturals',
      polarization: 'generally_liked'
    },
    default: {
      pattern: 'Rwandan origin',
      character: 'clean, bright, often citrus-forward',
      polarization: 'generally_liked'
    }
  },

  burundi: {
    default: {
      pattern: 'Burundian',
      character: 'bright, complex, berry and citrus notes',
      expect: 'Similar to Rwandan - clean, nuanced, elegant',
      polarization: 'generally_liked'
    }
  },

  uganda: {
    default: {
      pattern: 'Ugandan origin',
      character: 'chocolate, fruit, emerging specialty region',
      expect: 'Less predictable than neighbors - worth exploring',
      polarization: 'generally_liked',
      rarity: 'uncommon'
    }
  },

  congo: {
    default: {
      pattern: 'Congolese origin',
      character: 'complex, fruity, emerging specialty',
      expect: 'Rare African gem - unique terroir',
      polarization: 'generally_liked',
      rarity: 'rare'
    }
  },

  malawi: {
    default: {
      pattern: 'Malawian origin',
      character: 'bright, citrus, mild sweetness',
      expect: 'Clean African character, underrated',
      polarization: 'crowd_pleaser',
      rarity: 'uncommon'
    }
  },

  // === CENTRAL & SOUTH AMERICA ===

  colombia: {
    washed: {
      pattern: 'Textbook Colombian',
      character: 'balanced, chocolate-caramel, approachable',
      expect: 'No surprises - the "safe" specialty coffee',
      polarization: 'crowd_pleaser'
    },
    natural: {
      pattern: 'Colombian natural',
      character: 'fruit-forward twist on classic Colombian',
      expect: 'More fruit than traditional, still balanced',
      polarization: 'generally_liked'
    },
    honey: {
      pattern: 'Honey-processed Colombian',
      character: 'sweet, fruity, balanced body',
      expect: 'Sweetness enhanced, maintains Colombian character',
      polarization: 'crowd_pleaser'
    },
    anaerobic: {
      pattern: 'Experimental Colombian',
      character: 'unusual fruit esters, candy-like, possibly boozy',
      expect: 'Won\'t taste like "normal" coffee - wild and funky',
      polarization: 'adventurous_only'
    },
    default: {
      pattern: 'Colombian origin',
      character: 'balanced, nutty, easy drinking',
      polarization: 'crowd_pleaser'
    }
  },

  brazil: {
    natural: {
      pattern: 'Brazilian natural',
      character: 'nutty, chocolatey, heavy body, low acidity',
      expect: 'Smooth, easy drinking, espresso-friendly',
      polarization: 'crowd_pleaser'
    },
    default: {
      pattern: 'Brazilian classic',
      character: 'nutty, chocolatey, low acidity, smooth',
      expect: 'The comfortable, familiar coffee experience',
      polarization: 'crowd_pleaser'
    }
  },

  guatemala: {
    washed: {
      pattern: 'Guatemalan washed',
      character: 'chocolate, spice, balanced acidity',
      expect: 'Rich and complex with subtle smoke or spice notes',
      polarization: 'crowd_pleaser'
    },
    default: {
      pattern: 'Guatemalan origin',
      character: 'chocolate, spice, often with subtle smoke',
      polarization: 'generally_liked'
    }
  },

  'costa rica': {
    honey: {
      pattern: 'Costa Rican honey',
      character: 'bright, sweet, clean fruit notes',
      expect: 'Known for exceptional honey processing',
      polarization: 'crowd_pleaser'
    },
    washed: {
      pattern: 'Costa Rican washed',
      character: 'bright, clean, citrus and honey sweetness',
      expect: 'Precise, refined, technically excellent',
      polarization: 'crowd_pleaser'
    },
    anaerobic: {
      pattern: 'Experimental Costa Rican',
      character: 'intense fruit, innovative processing',
      expect: 'Costa Rica leads in experimental processing',
      polarization: 'adventurous_only'
    },
    default: {
      pattern: 'Costa Rican origin',
      character: 'bright, clean, often honey-sweet',
      polarization: 'crowd_pleaser'
    }
  },

  peru: {
    default: {
      pattern: 'Peruvian origin',
      character: 'mild, balanced, nutty-sweet',
      expect: 'Gentle, approachable, good value',
      polarization: 'crowd_pleaser'
    }
  },

  panama: {
    geisha: {
      pattern: 'Panama Geisha',
      character: 'jasmine, bergamot, tea-like, exceptional',
      expect: 'The pinnacle of specialty coffee - delicate and floral',
      polarization: 'generally_liked',
      rarity: 'rare'
    },
    default: {
      pattern: 'Panamanian origin',
      character: 'clean, bright, often exceptional quality',
      polarization: 'generally_liked'
    }
  },

  honduras: {
    default: {
      pattern: 'Honduran origin',
      character: 'sweet, fruity, good value specialty',
      expect: 'Underrated origin - often excellent quality for price',
      polarization: 'crowd_pleaser'
    }
  },

  nicaragua: {
    default: {
      pattern: 'Nicaraguan origin',
      character: 'balanced, fruity, chocolate undertones',
      expect: 'Reliable Central American character',
      polarization: 'crowd_pleaser'
    }
  },

  'el salvador': {
    default: {
      pattern: 'El Salvador origin',
      character: 'balanced, honey-sweet, stone fruit',
      expect: 'Often from bourbon variety - sweet and refined',
      polarization: 'generally_liked'
    }
  },

  mexico: {
    default: {
      pattern: 'Mexican origin',
      character: 'mild, nutty, chocolate notes',
      expect: 'Gentle, easy drinking, often organic',
      polarization: 'crowd_pleaser'
    }
  },

  ecuador: {
    default: {
      pattern: 'Ecuadorian origin',
      character: 'chocolate, caramel, medium body',
      expect: 'Emerging specialty region - worth exploring',
      polarization: 'generally_liked',
      rarity: 'uncommon'
    }
  },

  bolivia: {
    default: {
      pattern: 'Bolivian origin',
      character: 'bright, floral, complex',
      expect: 'High altitude growing - unique character',
      polarization: 'generally_liked',
      rarity: 'uncommon'
    }
  },

  // === ASIA & OCEANIA ===

  indonesia: {
    'wet hulled': {
      pattern: 'Sumatran wet-hulled',
      character: 'earthy, herbal, heavy body, low acidity',
      expect: 'The classic "Sumatran" taste - love it or hate it',
      polarization: 'love_or_hate'
    },
    washed: {
      pattern: 'Washed Indonesian',
      character: 'cleaner than traditional, still earthy undertones',
      expect: 'Unusual for Indonesia - more approachable',
      polarization: 'generally_liked'
    },
    default: {
      pattern: 'Indonesian origin',
      character: 'earthy, herbal, full body',
      expect: 'Bold, rustic character unlike anywhere else',
      polarization: 'love_or_hate'
    }
  },

  sumatra: {
    default: {
      pattern: 'Sumatran classic',
      character: 'earthy, herbal, cedar, heavy body',
      expect: 'Unmistakable - forest floor, spice, low acidity',
      polarization: 'love_or_hate'
    }
  },

  java: {
    default: {
      pattern: 'Java origin',
      character: 'earthy, spicy, full body',
      expect: 'Classic Indonesian character with refinement',
      polarization: 'generally_liked'
    }
  },

  'papua new guinea': {
    default: {
      pattern: 'Papua New Guinea origin',
      character: 'fruity, complex, wild character',
      expect: 'Unpredictable but often exciting',
      polarization: 'generally_liked',
      rarity: 'uncommon'
    }
  },

  india: {
    monsooned: {
      pattern: 'Monsooned Malabar',
      character: 'muted acidity, spicy, musty, heavy body',
      expect: 'Unique processing - exposed to monsoon winds',
      polarization: 'love_or_hate'
    },
    default: {
      pattern: 'Indian origin',
      character: 'spicy, earthy, full body',
      expect: 'Bold, distinctive character',
      polarization: 'generally_liked'
    }
  },

  vietnam: {
    robusta: {
      pattern: 'Vietnamese robusta',
      character: 'bold, bitter, high caffeine',
      expect: 'Strong, punchy - often used for Vietnamese iced coffee',
      polarization: 'love_or_hate'
    },
    default: {
      pattern: 'Vietnamese origin',
      character: 'bold, chocolatey, often robusta blend',
      polarization: 'generally_liked'
    }
  },

  china: {
    default: {
      pattern: 'Rare Chinese origin',
      character: 'emerging specialty region, Yunnan highlands',
      expect: 'Exciting new origin - expect surprises',
      polarization: 'adventurous_only',
      rarity: 'very_rare'
    }
  },

  yunnan: {
    default: {
      pattern: 'Yunnan, China',
      character: 'tea-like, floral, emerging specialty',
      expect: 'From tea country - often delicate and complex',
      polarization: 'generally_liked',
      rarity: 'rare'
    }
  },

  yemen: {
    default: {
      pattern: 'Yemeni heritage',
      character: 'wild, wine-like, dried fruit, complex',
      expect: 'Ancient coffee tradition - rare and precious',
      polarization: 'love_or_hate',
      rarity: 'very_rare'
    }
  },

  thailand: {
    default: {
      pattern: 'Thai origin',
      character: 'mild, sweet, emerging specialty',
      expect: 'Southeast Asian specialty - worth trying',
      polarization: 'generally_liked',
      rarity: 'uncommon'
    }
  },

  myanmar: {
    default: {
      pattern: 'Myanmar origin',
      character: 'fruity, bright, emerging specialty',
      expect: 'New specialty region - exciting potential',
      polarization: 'generally_liked',
      rarity: 'rare'
    }
  },

  // === ISLANDS ===

  hawaii: {
    kona: {
      pattern: 'Hawaiian Kona',
      character: 'mild, sweet, nutty, clean',
      expect: 'Gentle, refined - American-grown specialty',
      polarization: 'crowd_pleaser',
      rarity: 'rare'
    },
    default: {
      pattern: 'Hawaiian origin',
      character: 'mild, balanced, tropical sweetness',
      polarization: 'generally_liked',
      rarity: 'rare'
    }
  },

  jamaica: {
    'blue mountain': {
      pattern: 'Jamaica Blue Mountain',
      character: 'mild, balanced, sweet, no bitterness',
      expect: 'Legendary - smooth and refined',
      polarization: 'crowd_pleaser',
      rarity: 'very_rare'
    },
    default: {
      pattern: 'Jamaican origin',
      character: 'mild, sweet, balanced',
      polarization: 'generally_liked',
      rarity: 'rare'
    }
  }
};

// -----------------------------------------
// PROCESS KNOWLEDGE
// Explains how processing affects flavor
// -----------------------------------------
const PROCESS_INSIGHTS = {
  washed: {
    effect: 'clean, origin-forward character',
    why: 'Fruit removed before drying - pure bean flavor',
    body_tendency: 'lighter'
  },
  natural: {
    effect: 'fruit-forward, heavy body, fermented notes',
    why: 'Dried inside the cherry - fruit sugars infuse the bean',
    body_tendency: 'heavier'
  },
  honey: {
    effect: 'sweet, balanced, some fruit',
    why: 'Partial fruit left during drying - middle ground',
    body_tendency: 'medium'
  },
  'black honey': {
    effect: 'intense sweetness, fruit-forward',
    why: 'Maximum fruit mucilage retained - almost like natural',
    body_tendency: 'heavier'
  },
  'red honey': {
    effect: 'sweet, fruity, balanced',
    why: 'Moderate mucilage retained',
    body_tendency: 'medium'
  },
  'yellow honey': {
    effect: 'clean with subtle sweetness',
    why: 'Minimal mucilage retained - closer to washed',
    body_tendency: 'lighter'
  },
  'white honey': {
    effect: 'clean, bright, hint of sweetness',
    why: 'Least mucilage - very close to washed',
    body_tendency: 'lighter'
  },
  anaerobic: {
    effect: 'unusual fruit esters, candy-like, possibly boozy',
    why: 'Oxygen-free fermentation creates unique flavors',
    body_tendency: 'heavier'
  },
  'anaerobic natural': {
    effect: 'intense fruit, wild, funky',
    why: 'Natural process plus oxygen-free fermentation - maximum flavor',
    body_tendency: 'heavier'
  },
  'anaerobic washed': {
    effect: 'clean but with unusual fruit notes',
    why: 'Controlled fermentation before washing',
    body_tendency: 'medium'
  },
  carbonic: {
    effect: 'bright, juicy, clean fruit',
    why: 'CO2 fermentation - borrowed from wine making',
    body_tendency: 'medium'
  },
  'carbonic maceration': {
    effect: 'wine-like, berry, bright acidity',
    why: 'Whole cherry CO2 fermentation - very wine-like',
    body_tendency: 'medium'
  },
  'wet hulled': {
    effect: 'earthy, herbal, heavy body',
    why: 'Traditional Indonesian method - unique character',
    body_tendency: 'heavier'
  },
  experimental: {
    effect: 'unpredictable, innovative, often intense',
    why: 'New processing techniques - expect the unexpected',
    body_tendency: 'varies'
  },
  lactic: {
    effect: 'creamy, yogurt-like, tangy sweetness',
    why: 'Lactic acid fermentation - dairy-like notes',
    body_tendency: 'heavier'
  },
  'extended fermentation': {
    effect: 'intense fruit, wine-like, complex',
    why: 'Longer fermentation develops deeper flavors',
    body_tendency: 'heavier'
  }
};

// -----------------------------------------
// VARIETY KNOWLEDGE (Optional enhancement)
// -----------------------------------------
const VARIETY_INSIGHTS = {
  geisha: {
    character: 'floral, tea-like, jasmine, bergamot',
    note: 'The most prized variety - delicate and complex'
  },
  bourbon: {
    character: 'sweet, balanced, caramel',
    note: 'Classic variety - refined sweetness'
  },
  typica: {
    character: 'clean, sweet, balanced',
    note: 'The original arabica - elegant simplicity'
  },
  sl28: {
    character: 'bright, complex, blackcurrant',
    note: 'Kenyan classic - intense and fruity'
  },
  sl34: {
    character: 'balanced, citrus, complex',
    note: 'Kenyan variety - bright and refined'
  },
  caturra: {
    character: 'bright, citrus, clean',
    note: 'Bourbon mutation - lively and crisp'
  },
  catuai: {
    character: 'balanced, sweet, mild',
    note: 'Hardy variety - consistent quality'
  },
  pacamara: {
    character: 'complex, floral, fruity, full body',
    note: 'Large bean - bold and interesting'
  },
  heirloom: {
    character: 'complex, varied, often wild',
    note: 'Ethiopian landraces - unique genetic diversity'
  },
  catimor: {
    character: 'varied, sometimes bitter notes',
    note: 'Disease-resistant hybrid - quality improving'
  },
  castillo: {
    character: 'balanced, mild, clean',
    note: 'Colombian variety - reliable quality'
  },
  'pink bourbon': {
    character: 'floral, sweet, complex',
    note: 'Rare bourbon mutation - exceptional sweetness'
  },
  'yellow bourbon': {
    character: 'sweet, fruity, smooth',
    note: 'Yellow cherry bourbon - enhanced sweetness'
  },
  maragogype: {
    character: 'mild, smooth, low acidity',
    note: 'Giant bean - gentle and approachable'
  },
  liberica: {
    character: 'bold, smoky, jackfruit, unusual',
    note: 'Rare species - distinctive and polarizing'
  }
};

// -----------------------------------------
// BODY DESCRIPTIONS
// -----------------------------------------
const BODY_DESCRIPTIONS = {
  very_light: 'Delicate, tea-like - almost weightless on the tongue',
  light: 'Light and clean - refreshing, easy drinking',
  medium_light: 'Smooth with gentle presence',
  medium: 'Balanced mouthfeel - neither light nor heavy',
  medium_heavy: 'Rich and satisfying - noticeable weight',
  heavy: 'Creamy, syrupy - coats the tongue',
  very_heavy: 'Dense, velvety - thick and luxurious'
};

// -----------------------------------------
// ACIDITY DESCRIPTIONS
// -----------------------------------------
const ACIDITY_DESCRIPTIONS = {
  very_low: 'Muted, smooth - no sharp edges',
  low: 'Gentle, mellow - soft and approachable',
  medium_low: 'Subtle brightness - there but not dominant',
  medium: 'Balanced acidity - present but integrated',
  medium_high: 'Bright and lively - noticeable zing',
  high: 'Sparkling, vibrant - the star of the show',
  very_high: 'Intense, electric - bold and punchy'
};

// -----------------------------------------
// AUDIENCE / POLARIZATION TEXT
// -----------------------------------------
const POLARIZATION_TEXT = {
  crowd_pleaser: 'Crowd pleaser - safe for gifts, new drinkers, everyone',
  generally_liked: 'Easy to like, minimal surprises',
  love_or_hate: 'Polarizing - people either love or hate this style',
  adventurous_only: 'For adventurous palates. Not your everyday coffee.'
};

// -----------------------------------------
// RARITY TEXT
// -----------------------------------------
const RARITY_TEXT = {
  common: null,
  uncommon: 'Less common origin - worth exploring',
  rare: 'Rare find - not often seen in the UK',
  very_rare: 'Very rare - exceptional opportunity'
};

// ============================================
// MAIN FUNCTION: Generate Barista Perspective
// ============================================

function generateBaristaPerspective(product) {
  const origin = (product.origin || '').toLowerCase().trim();
  const region = (product.region || '').toLowerCase().trim();
  const process = (product.process || '').toLowerCase().trim();
  const variety = (product.variety || '').toLowerCase().trim();
  const roastLevel = (product.roast_level || product.roastLevel || '').toLowerCase().trim();

  // Character axes: [acidity, body, roast, complexity]
  let characterAxes = product.characterAxesJson || product.character_axes;
  if (typeof characterAxes === 'string') {
    try {
      characterAxes = JSON.parse(characterAxes);
    } catch (e) {
      characterAxes = [0, 0, 0, 0];
    }
  }
  characterAxes = characterAxes || [0, 0, 0, 0];

  const acidity = characterAxes[0] ?? 0;
  const body = characterAxes[1] ?? 0;
  const roast = characterAxes[2] ?? 0;
  const complexity = characterAxes[3] ?? 0;

  // -----------------------------------------
  // 1. Find origin pattern
  // -----------------------------------------
  let originKey = Object.keys(ORIGIN_PATTERNS).find(key =>
    origin.includes(key) || region.includes(key)
  );
  let originData = ORIGIN_PATTERNS[originKey] || null;

  // -----------------------------------------
  // 2. Find process match within origin
  // -----------------------------------------
  let processKey = null;
  if (originData) {
    processKey = Object.keys(originData).find(key =>
      key !== 'default' && process.includes(key)
    );
  }

  let pattern = originData?.[processKey] || originData?.['default'] || null;

  // -----------------------------------------
  // 3. Get process insight (standalone)
  // -----------------------------------------
  let processInsightKey = Object.keys(PROCESS_INSIGHTS).find(key =>
    process.includes(key)
  );
  let processInsight = PROCESS_INSIGHTS[processInsightKey] || null;

  // -----------------------------------------
  // 4. Get variety insight (if available)
  // -----------------------------------------
  let varietyInsightKey = Object.keys(VARIETY_INSIGHTS).find(key =>
    variety.includes(key)
  );
  let varietyInsight = VARIETY_INSIGHTS[varietyInsightKey] || null;

  // -----------------------------------------
  // 5. Calculate body description from axes
  // -----------------------------------------
  let bodyKey;
  if (body <= -0.6) bodyKey = 'very_light';
  else if (body <= -0.3) bodyKey = 'light';
  else if (body <= -0.1) bodyKey = 'medium_light';
  else if (body <= 0.1) bodyKey = 'medium';
  else if (body <= 0.3) bodyKey = 'medium_heavy';
  else if (body <= 0.6) bodyKey = 'heavy';
  else bodyKey = 'very_heavy';

  const bodyDescription = BODY_DESCRIPTIONS[bodyKey];

  // -----------------------------------------
  // 6. Calculate acidity description from axes
  // -----------------------------------------
  let acidityKey;
  if (acidity <= -0.6) acidityKey = 'very_low';
  else if (acidity <= -0.3) acidityKey = 'low';
  else if (acidity <= -0.1) acidityKey = 'medium_low';
  else if (acidity <= 0.1) acidityKey = 'medium';
  else if (acidity <= 0.3) acidityKey = 'medium_high';
  else if (acidity <= 0.6) acidityKey = 'high';
  else acidityKey = 'very_high';

  const acidityDescription = ACIDITY_DESCRIPTIONS[acidityKey];

  // -----------------------------------------
  // 7. Build result object
  // -----------------------------------------
  const result = {
    // What it is
    pattern: pattern?.pattern || formatOriginName(product.origin) + ' origin',
    character: pattern?.character || null,

    // What to expect
    expect: pattern?.expect || null,

    // Why it tastes this way (from process)
    processEffect: processInsight?.effect || null,
    processWhy: processInsight?.why || null,

    // Variety note (if notable)
    varietyNote: varietyInsight?.note || null,

    // Mouthfeel descriptions
    bodyFeel: bodyDescription,
    acidityFeel: acidityDescription,

    // Who is this for
    audience: POLARIZATION_TEXT[pattern?.polarization] || null,

    // Rarity
    rarity: RARITY_TEXT[pattern?.rarity] || null,
    isRare: pattern?.rarity === 'rare' || pattern?.rarity === 'very_rare' || !originKey
  };

  return result;
}

// ============================================
// HELPER: Format origin name nicely
// ============================================
function formatOriginName(origin) {
  if (!origin) return 'Unknown';
  return origin
    .split(' ')
    .map(word => word.charAt(0).toUpperCase() + word.slice(1).toLowerCase())
    .join(' ');
}

// ============================================
// HELPER: Generate full description paragraph
// ============================================
function generateBaristaDescription(product) {
  const p = generateBaristaPerspective(product);

  const sentences = [];

  // 1. Pattern + character (what it is)
  if (p.pattern) {
    let opening = p.pattern;
    if (p.character) {
      opening += ' - ' + p.character;
    }
    sentences.push(opening + '.');
  }

  // 2. Process explanation (why it tastes this way)
  if (p.processWhy) {
    sentences.push(p.processWhy + '.');
  }

  // 3. Body feel (what it feels like)
  if (p.bodyFeel) {
    sentences.push(p.bodyFeel + '.');
  }

  // 4. Audience (who it's for)
  if (p.audience) {
    sentences.push(p.audience);
  }

  // 5. Rarity note (if rare)
  if (p.rarity) {
    sentences.push(p.rarity + '.');
  }

  return sentences.join(' ');
}

// ============================================
// HELPER: Generate short emoji summary line
// ============================================
function generateEmojiSummary(product) {
  // Character axes: [acidity, body, roast, complexity]
  let characterAxes = product.characterAxesJson || product.character_axes;
  if (typeof characterAxes === 'string') {
    try {
      characterAxes = JSON.parse(characterAxes);
    } catch (e) {
      characterAxes = [0, 0, 0, 0];
    }
  }
  characterAxes = characterAxes || [0, 0, 0, 0];

  const acidity = characterAxes[0] ?? 0;
  const body = characterAxes[1] ?? 0;
  const roast = characterAxes[2] ?? 0;
  const complexity = characterAxes[3] ?? 0;

  const parts = [];

  // Acidity
  if (acidity > 0.3) parts.push('Bright');
  else if (acidity > 0) parts.push('Medium-bright');
  else if (acidity > -0.3) parts.push('Balanced');
  else parts.push('Mellow');

  // Body
  if (body > 0.3) parts.push('Full body');
  else if (body > 0) parts.push('Medium-full');
  else if (body > -0.3) parts.push('Medium body');
  else parts.push('Light body');

  // Roast
  if (roast > 0.3) parts.push('Dark roast');
  else if (roast > 0) parts.push('Medium-dark');
  else if (roast > -0.3) parts.push('Medium roast');
  else parts.push('Light roast');

  // Complexity
  if (complexity > 0.5) parts.push('Very complex');
  else if (complexity > 0.2) parts.push('Complex');
  else if (complexity > -0.2) parts.push('Balanced');
  else parts.push('Straightforward');

  return parts.join('  |  ');
}

// ============================================
// EXPOSE FUNCTIONS GLOBALLY
// ============================================
window.BaristaKnowledge = {
  generateBaristaPerspective,
  generateBaristaDescription,
  generateEmojiSummary,
  ORIGIN_PATTERNS,
  PROCESS_INSIGHTS,
  VARIETY_INSIGHTS
};
