package com.uniride.security;

import com.uniride.exceptions.DemasiadasSolicitudesException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

public class LimiteAuthFilter extends OncePerRequestFilter {
    private final LimiteAutenticacion limite;
    public LimiteAuthFilter(LimiteAutenticacion limite) { this.limite = limite; }

    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
            FilterChain chain) throws ServletException, IOException {
        if (req.getServletPath().startsWith("/auth/") && req.getMethod().equals("POST")) {
            try { limite.ip(req.getRemoteAddr()); }
            catch (DemasiadasSolicitudesException e) {
                res.setStatus(429); res.setContentType("application/json");
                res.setHeader("Retry-After", "900");
                res.getWriter().write("{\"mensaje\":\"Demasiados intentos. Espera antes de volver a intentar.\"}");
                return;
            }
        }
        chain.doFilter(req, res);
    }
}
