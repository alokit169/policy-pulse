package com.policypulse.customers;

import com.policypulse.common.Domain;
import com.policypulse.common.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Every route is scoped to the caller's organization by the service; none of
 * these methods accept an organization id from the client.
 */
@RestController
@RequestMapping("/api/customers")
@Tag(name = "Customers")
public class CustomerController {
    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @GetMapping
    @Operation(summary = "Search customers in the caller's organization")
    public PageResponse<CustomerResponse> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Domain.EntityStatus status,
            @RequestParam(required = false) UUID agentId,
            @PageableDefault(size = 20, sort = {"lastName", "firstName"}, direction = Sort.Direction.ASC)
            Pageable pageable) {
        return customerService.search(q, status, agentId, pageable);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch one customer")
    public CustomerResponse get(@PathVariable UUID id) {
        return customerService.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a customer")
    public CustomerResponse create(@Valid @RequestBody CustomerRequest request) {
        return customerService.create(request);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a customer")
    public CustomerResponse update(@PathVariable UUID id, @Valid @RequestBody CustomerRequest request) {
        return customerService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Archive a customer, keeping their history")
    public void archive(@PathVariable UUID id) {
        customerService.archive(id);
    }

    @PostMapping("/{id}/restore")
    @Operation(summary = "Return an archived customer to active")
    public CustomerResponse restore(@PathVariable UUID id) {
        return customerService.restore(id);
    }
}
