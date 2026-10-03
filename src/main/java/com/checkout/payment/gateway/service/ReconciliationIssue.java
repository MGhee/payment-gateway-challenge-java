package com.checkout.payment.gateway.service;

public record ReconciliationIssue(String type, String reference, String details) {
}