package com.certcopilot.platform.ai;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * The complete AI surface of the application. Six operations are planned for the
 * MVP; S0 registers only the skeleton echo operation.
 */
@Component
public class AiOperationRegistry {

    private final Map<String, AiOperation> operations = new LinkedHashMap<>();

    public void register(AiOperation operation) {
        if (operations.putIfAbsent(operation.id(), operation) != null) {
            throw new IllegalStateException("duplicate AI operation id: " + operation.id());
        }
    }

    public AiOperation require(String operationId) {
        AiOperation op = operations.get(operationId);
        if (op == null) {
            throw new IllegalArgumentException("unknown AI operation: " + operationId);
        }
        return op;
    }

    public Set<String> registeredIds() {
        return Set.copyOf(operations.keySet());
    }
}
