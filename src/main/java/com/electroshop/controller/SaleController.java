
package com.electroshop.controller;

import com.electroshop.entity.Customer;
import com.electroshop.entity.Payment;
import com.electroshop.entity.Product;
import com.electroshop.entity.Sale;
import com.electroshop.entity.SaleItem;
import com.electroshop.repository.CustomerRepository;
import com.electroshop.repository.PaymentRepository;
import com.electroshop.repository.ProductRepository;
import com.electroshop.repository.SaleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/sales")
@RequiredArgsConstructor
public class SaleController {

    private final SaleRepository saleRepo;
    private final CustomerRepository customerRepo;
    private final ProductRepository productRepo;
    private final PaymentRepository paymentRepo;

    @GetMapping
    public List<Sale> getSales(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        if (from != null && to != null) {
            LocalDateTime start = LocalDate.parse(from).atStartOfDay();
            LocalDateTime end = LocalDate.parse(to).atTime(23, 59, 59);
            return saleRepo.findBySaleDateBetween(start, end);
        }
        return saleRepo.findAll();
    }

    @GetMapping("/daily")
    public Map<String, Object> getDailySummary() {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        Map<String, Object> result = new HashMap<>();
        result.put("total",  saleRepo.getTodayTotal(startOfDay));
        result.put("profit", saleRepo.getTodayProfit(startOfDay));
        result.put("count",  saleRepo.getTodayCount(startOfDay));
        return result;
    }

    @GetMapping("/monthly")
    public Map<String, Object> getMonthlySummary(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        LocalDateTime start = from != null
                ? LocalDate.parse(from).atStartOfDay()
                : LocalDate.now().withDayOfMonth(1).atStartOfDay();
        LocalDateTime end = to != null
                ? LocalDate.parse(to).atTime(23, 59, 59)
                : LocalDate.now().atTime(23, 59, 59);
        List<Sale> sales = saleRepo.findBySaleDateBetween(start, end);
        double total  = sales.stream().mapToDouble(s -> s.getTotalAmount() != null ? s.getTotalAmount() : 0).sum();
        double profit = sales.stream().mapToDouble(s -> s.getProfit()      != null ? s.getProfit()      : 0).sum();
        Map<String, Object> result = new HashMap<>();
        result.put("total",  total);
        result.put("profit", profit);
        result.put("count",  sales.size());
        return result;
    }

    @PostMapping
    public Sale createSale(@RequestBody Map<String, Object> body) {

        Sale sale = new Sale();
        sale.setSaleDate(LocalDateTime.now());
        sale.setTotalAmount(toDouble(body.get("totalAmount")));
        sale.setAmountPaid(toDouble(body.get("amountPaid")));
        sale.setDueAmount(toDouble(body.get("dueAmount")));
        sale.setProfit(toDouble(body.get("profit")));

        Customer customer = null;

        if (body.get("customerId") != null) {
            Long custId = toLong(body.get("customerId"));
            Optional<Customer> custOpt = customerRepo.findById(custId);
            if (custOpt.isPresent()) {
                customer = custOpt.get();
                sale.setCustomer(customer);
                Sale tempSave = saleRepo.save(sale);

                // Always ADD to existing due — never overwrite
                double existingDue = customer.getTotalDue() != null ? customer.getTotalDue() : 0.0;
                double newDue = existingDue + tempSave.getDueAmount();
                customer.setTotalDue(newDue);
                customer.setTotalPurchases(customer.getTotalPurchases() + 1);
                customerRepo.save(customer);

                // Save payment record if customer paid something
                if (tempSave.getAmountPaid() > 0) {
                    Payment payment = Payment.builder()
                            .customer(customer)
                            .amount(tempSave.getAmountPaid())
                            .paymentDate(LocalDateTime.now())
                            .note("Paid ₹" + tempSave.getAmountPaid() +
                                  " for sale #" + tempSave.getId() +
                                  (tempSave.getDueAmount() > 0
                                    ? " — Due ₹" + tempSave.getDueAmount()
                                    : " — Fully paid"))
                            .build();
                    paymentRepo.save(payment);
                }

                return processSaleItems(body, tempSave);
            }
        }

        // Walk-in customer sale
        Sale saved = saleRepo.save(sale);

        if (saved.getAmountPaid() > 0) {
            Payment payment = Payment.builder()
                    .amount(saved.getAmountPaid())
                    .paymentDate(LocalDateTime.now())
                    .note("Walk-in sale #" + saved.getId())
                    .build();
            paymentRepo.save(payment);
        }

        return processSaleItems(body, saved);
    }

    private Sale processSaleItems(Map<String, Object> body, Sale saved) {
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
        if (items != null) {
            List<SaleItem> saleItems = new ArrayList<>();
            for (Map<String, Object> itemData : items) {
                Long productId = toLong(itemData.get("productId"));
                int qty = toInt(itemData.get("quantity"));
                Optional<Product> prodOpt = productRepo.findById(productId);
                if (prodOpt.isPresent()) {
                    Product product = prodOpt.get();
                    SaleItem saleItem = SaleItem.builder()
                            .sale(saved)
                            .product(product)
                            .quantity(qty)
                            .price(toDouble(itemData.get("price")))
                            .cost(toDouble(itemData.get("cost")))
                            .build();
                    saleItems.add(saleItem);
                    product.setStock(Math.max(0, product.getStock() - qty));
                    product.setLastSold(LocalDate.now());
                    productRepo.save(product);
                }
            }
            saved.setItems(saleItems);
            saleRepo.save(saved);
        }
        return saved;
    }

    private Double toDouble(Object val) {
        if (val == null) return 0.0;
        return ((Number) val).doubleValue();
    }

    private Long toLong(Object val) {
        if (val == null) return 0L;
        return ((Number) val).longValue();
    }

    private int toInt(Object val) {
        if (val == null) return 0;
        return ((Number) val).intValue();
    }
}