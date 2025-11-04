package com.coffee.beansfinder.init;

import com.coffee.beansfinder.entity.CoffeeProduct;
import com.coffee.beansfinder.repository.CoffeeProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Seeds the database with initial coffee products
 * Only runs in 'dev' profile
 */
@Component
@Profile("dev")
@RequiredArgsConstructor
@Slf4j
public class DataSeeder implements CommandLineRunner {

    private final CoffeeProductRepository productRepository;

    @Override
    public void run(String... args) {
        if (productRepository.count() > 0) {
            log.info("Database already has data, skipping seeding");
            return;
        }

        log.info("Seeding initial coffee products...");

        List<CoffeeProduct> products = List.of(
            CoffeeProduct.builder()
                .brand("Sweven Coffee")
                .productName("El Ocaso - Geisha")
                .sellerUrl("https://swevencoffee.co.uk/products/el-ocaso-geisha")
                .origin("Colombia")
                .region("Quindio")
                .process("Honey Anaerobic")
                .producer("Santiago Patiño")
                .variety("Geisha")
                .crawlStatus("pending")
                .build(),

            CoffeeProduct.builder()
                .brand("Pact Coffee")
                .productName("Colombian Single Origin")
                .origin("Colombia")
                .process("Washed")
                .crawlStatus("pending")
                .build(),

            CoffeeProduct.builder()
                .brand("Square Mile Coffee")
                .productName("Red Brick")
                .origin("Ethiopia")
                .crawlStatus("pending")
                .build(),

            CoffeeProduct.builder()
                .brand("Has Bean Coffee")
                .productName("Jailbreak Espresso")
                .crawlStatus("pending")
                .build(),

            CoffeeProduct.builder()
                .brand("Origin Coffee")
                .productName("Rwanda Simbi")
                .origin("Rwanda")
                .process("Washed")
                .crawlStatus("pending")
                .build()
        );

        productRepository.saveAll(products);
        log.info("Seeded {} coffee products", products.size());
    }
}
