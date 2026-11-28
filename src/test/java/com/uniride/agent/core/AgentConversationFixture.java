package com.uniride.agent.core;

import com.uniride.agent.app.dto.ChatResponse;
import com.uniride.agent.app.dto.OperacionResumen;
import com.uniride.entities.Usuario;
import java.util.List;
import java.util.UUID;

/** Records a real preparation receipt without involving the model. */
public final class AgentConversationFixture {
    private AgentConversationFixture() { }

    public static UUID recordProposal(AgentConversationStore conversations, Usuario owner, UUID conversationId,
                                      OperacionResumen proposal) {
        try (var lease = conversations.open(owner.getId(), owner.getRolPrincipal(), conversationId)) {
            lease.complete("Prepara esta operación",
                    new ChatResponse(proposal.detalle(), List.of(), List.of(), List.of(), proposal));
            return lease.id();
        }
    }
}
