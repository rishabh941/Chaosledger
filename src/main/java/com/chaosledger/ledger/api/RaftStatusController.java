package com.chaosledger.ledger.api;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.protocol.RaftPeerId;
import org.apache.ratis.server.RaftServer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Exposes the Raft node's current status: role, leader, term, peers.
 * Invaluable for debugging and for discovering the current leader.
 *
 * GET /api/raft/status
 */

@RestController
@RequestMapping("/api/raft")
@ConditionalOnProperty(name = "raft.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class RaftStatusController {

    private final RaftServer raftServer;

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
}