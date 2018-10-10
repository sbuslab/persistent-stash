package com.sbuslab.stash;

import javax.validation.constraints.NotNull;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Operation {

    @NotNull
    private String correlationId;

    @NotNull
    private String messageId;

    @NotNull
    private String routingKey;

    private String body;

    private String transportCorrelationId;

    @NotNull
    private Long createdAt;
}
