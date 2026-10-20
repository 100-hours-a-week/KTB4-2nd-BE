package com.yeodam.yeodambe.user.migration;

public record MigrationAuthCommand(String requestId, String operation, String credential, long createdAt) {}
