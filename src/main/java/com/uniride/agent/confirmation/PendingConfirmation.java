package com.uniride.agent.confirmation;

import com.uniride.entities.Usuario;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "agent_confirmaciones")
@Getter @Setter @NoArgsConstructor
public class PendingConfirmation {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32)
    private TipoOperacion tipo;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private EstadoConfirmacion estado;
    @Column(nullable = false, columnDefinition = "text") private String parametros;
    @Column(nullable = false, columnDefinition = "text") private String snapshot;
    @Column(nullable = false, columnDefinition = "text") private String resumen;
    @Column(name = "recurso_id") private Long recursoId;
    @Column(nullable = false) private Instant creada;
    @Column(nullable = false) private Instant expira;
    private Instant ejecutada;
    @Column(columnDefinition = "text") private String resultado;
}
