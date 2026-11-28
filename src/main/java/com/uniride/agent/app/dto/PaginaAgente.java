package com.uniride.agent.app.dto;

import java.util.List;
import org.springframework.data.domain.Page;

/** Bounded results plus enough information to request the following page. Pages start at zero. */
public record PaginaAgente<T>(List<T> items, int pagina, int tamano, long total, boolean hayMas) {
    public PaginaAgente {
        items = List.copyOf(items);
    }

    public static <T> PaginaAgente<T> from(Page<T> page) {
        return new PaginaAgente<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.hasNext());
    }
}
