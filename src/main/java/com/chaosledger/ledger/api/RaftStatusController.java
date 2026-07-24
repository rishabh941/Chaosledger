package com.chaosledger.ledger.api;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ratis.client.RaftClient;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.protocol.RaftPeerId;
import org.apache.ratis.server.RaftServer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/raft")
@ConditionalOnProperty(name = "raft.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class RaftStatusController {

    private final RaftServer raftServer;
    private final RaftClient raftClient;

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {

        Map<String, Object> status = new LinkedHashMap<>();

        try {
            RaftPeerId selfId = raftServer.getId();
            status.put("nodeId", selfId.toString());

            Iterator<RaftGroupId> iterator = raftServer.getGroupIds().iterator();

            if (!iterator.hasNext()) {
                status.put("role", "NO_GROUP");
                status.put("error", "Server has no Raft groups");
                return ResponseEntity.ok(status);
            }

            RaftGroupId groupId = iterator.next();

            var division = raftServer.getDivision(groupId);
            var info = division.getInfo();

            status.put("groupId", groupId.toString());
            status.put("role", info.getCurrentRole().name());
            status.put("leaderId",
                    info.getLeaderId() == null
                            ? "unknown"
                            : info.getLeaderId().toString());
            status.put("term", info.getCurrentTerm());
            status.put("commitIndex", division.getRaftLog().getLastCommittedIndex());
            status.put("logIndex", division.getStateMachine().getLastAppliedTermIndex() != null
                    ? division.getStateMachine().getLastAppliedTermIndex().getIndex() : 0);

            status.put("peers",
                    division.getGroup()
                            .getPeers()
                            .stream()
                            .map(peer -> peer.getId().toString())
                            .collect(Collectors.toList()));

        } catch (Exception e) {
            log.error("Failed to fetch Raft status", e);

            status.put("error", e.getClass().getSimpleName());
            status.put("message", e.getMessage());
        }

        return ResponseEntity.ok(status);
    }

    @PostMapping("/transfer-leadership")
    public ResponseEntity<Map<String, String>> transferLeadership() {
        try {
            Iterator<RaftGroupId> iterator = raftServer.getGroupIds().iterator();
            if (!iterator.hasNext()) {
                return ResponseEntity.badRequest().body(Map.of("error", "No Raft group"));
            }

            RaftGroupId groupId = iterator.next();
            var division = raftServer.getDivision(groupId);
            var info = division.getInfo();

            RaftPeerId currentLeaderId = info.getLeaderId();
            if (currentLeaderId == null) {
                return ResponseEntity.ok(Map.of("action", "skipped", "reason", "No leader elected yet"));
            }

            var peers = division.getGroup().getPeers().stream()
                    .filter(p -> !p.getId().equals(currentLeaderId))
                    .collect(Collectors.toList());

            if (peers.isEmpty()) {
                return ResponseEntity.ok(Map.of("action", "skipped", "reason", "No other peers"));
            }

            Collections.shuffle(peers);
            for (var peer : peers) {
                RaftPeerId candidate = peer.getId();
                try {
                    log.info("Attempting leadership transfer: {} → {}", currentLeaderId, candidate);
                    raftClient.admin().transferLeadership(candidate, 10000);
                    return ResponseEntity.ok(Map.of(
                            "action", "transfer_initiated",
                            "from", currentLeaderId.toString(),
                            "to", candidate.toString()
                    ));
                } catch (Exception e) {
                    log.warn("Transfer to {} failed: {}, trying next peer...", candidate, e.getMessage());
                }
            }
            return ResponseEntity.ok(Map.of(
                    "action", "skipped",
                    "reason", "All peers unreachable or timed out"
            ));
        } catch (Exception e) {
            log.error("Leadership transfer failed", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", e.getClass().getSimpleName(),
                    "message", e.getMessage() != null ? e.getMessage() : "unknown"
            ));
        }
    }
}
