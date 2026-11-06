package com.uniride.security;

import static org.assertj.core.api.Assertions.*;
import com.uniride.exceptions.DemasiadasSolicitudesException;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.test.util.ReflectionTestUtils;

class LimiteAutenticacionTest {
    @Test void limitaCuentaEIpYRecuperaPresupuestoTrasVentana() throws Exception {
        var limite=new LimiteAutenticacion();
        ReflectionTestUtils.setField(limite,"limiteCuenta",2);
        ReflectionTestUtils.setField(limite,"limiteIp",2);
        Instant ahora=Instant.parse("2026-11-05T15:00:00Z");
        ReflectionTestUtils.setField(limite,"clock",Clock.fixed(ahora,ZoneOffset.UTC));
        limite.cuenta("ana@upc.edu.pe"); limite.cuenta("ana@upc.edu.pe");
        assertThatThrownBy(() -> limite.cuenta("ana@upc.edu.pe")).isInstanceOf(DemasiadasSolicitudesException.class);
        var filtro=new LimiteAuthFilter(limite);
        for(int i=0;i<3;i++) {
            var req=new MockHttpServletRequest("POST","/auth/login"); req.setServletPath("/auth/login");
            var res=new MockHttpServletResponse(); filtro.doFilter(req,res,new MockFilterChain());
            assertThat(res.getStatus()).isEqualTo(i==2 ? 429 : 200);
        }
        ReflectionTestUtils.setField(limite,"clock",Clock.fixed(ahora.plusSeconds(900),ZoneOffset.UTC));
        limite.cuenta("ana@upc.edu.pe"); limite.ip("127.0.0.1");
    }
    @Test void normalizacionInstitucionalYTelefonoRechazaRepresentacionesInvalidas() {
        assertThat(com.uniride.util.ValidacionUtil.normalizarCorreo(" ANA@UPC.EDU.PE ")).isEqualTo("ana@upc.edu.pe");
        assertThat(com.uniride.util.ValidacionUtil.esCorreoValido("ana@upc.edu.attacker.com")).isFalse();
        for(String formato:new String[]{"987654321","+51 987-654-321","0051987654321"})
            assertThat(com.uniride.util.ValidacionUtil.normalizarTelefono(formato)).isEqualTo("+51987654321");
        for(String invalido:new String[]{"------","123456","++++++","000000000"})
            assertThatThrownBy(() -> com.uniride.util.ValidacionUtil.normalizarTelefono(invalido)).isInstanceOf(RuntimeException.class);
    }
}
