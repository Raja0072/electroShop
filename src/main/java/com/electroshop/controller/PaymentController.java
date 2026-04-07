package com.electroshop.controller;

import com.electroshop.entity.Customer;
import com.electroshop.entity.Payment;
import com.electroshop.repository.CustomerRepository;
import com.electroshop.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentRepository paymentRepo;
    private final CustomerRepository customerRepo;

    @GetMapping("/customer/{customerId}")
    public List<Payment> getByCustomer(@PathVariable Long customerId) {
        return paymentRepo.findByCustomerIdOrderByPaymentDateDesc(customerId);
    }

    @PostMapping
    public Payment createPayment(@RequestBody Map<String, Object> body) {
        Long customerId = ((Number) body.get("customerId")).longValue();
        Double amount = ((Number) body.get("amount")).doubleValue();
        String note = body.get("note") != null ? body.get("note").toString() : "Due payment collected";

        Optional<Customer> custOpt = customerRepo.findById(customerId);
        if (!custOpt.isPresent()) {
            throw new RuntimeException("Customer not found");
        }

        Customer customer = custOpt.get();

        // Update customer due amount
        double newDue = Math.max(0, customer.getTotalDue() - amount);
        customer.setTotalDue(newDue);
        customerRepo.save(customer);

        // Save payment record
        Payment payment = Payment.builder()
                .customer(customer)
                .amount(amount)
                .paymentDate(LocalDateTime.now())
                .note(note)
                .build();

        return paymentRepo.save(payment);
    }
}