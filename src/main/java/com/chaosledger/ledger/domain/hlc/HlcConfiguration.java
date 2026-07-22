package com.chaosledger.ledger.infrastructure.hlc;

import com.chaosledger.ledger.domain.hlc.AdjustableClock;
import com.chaosledger.ledger.domain.hlc.HybridLogicalClock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates the singleton HybridLogicalClock bean for this node.
 *
 * In Raft mode, the node ID comes from raft.node.id.
 * In single-node mode (tests), it defaults to "single".
 */
@Configuration
@Slf4j
public class HlcConfiguration {

    @Value("${raft.node.id:single}")
    private String nodeId;

    @Bean
    public AdjustableClock adjustableClock() {
        return new AdjustableClock();
    }

    @Bean
    public HybridLogicalClock hybridLogicalClock(AdjustableClock adjustableClock) {
        log.info("Creating HybridLogicalClock for node: {}", nodeId);
        return new HybridLogicalClock(nodeId, adjustableClock);
    }

}