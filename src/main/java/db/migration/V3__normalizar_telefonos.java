package db.migration;

import com.uniride.util.ValidacionUtil;
import com.uniride.exceptions.CamposInvalidosException;
import java.util.*;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.migration.*;

/** La misma metadata telefónica que usa registro; no intentar imitarla con regex SQL. */
public class V3__normalizar_telefonos extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        Map<Long,String> cambios = new LinkedHashMap<>();
        Set<String> vistos = new HashSet<>();
        try (var statement=context.getConnection().createStatement();
             var filas=statement.executeQuery("SELECT id,telefono FROM usuarios ORDER BY id")) {
            while(filas.next()) {
                String original=filas.getString("telefono");
                try {
                    String canonico=ValidacionUtil.normalizarTelefono(original);
                    if (!vistos.add(canonico)) throw new FlywayException(
                            "Existen teléfonos equivalentes. Resolver duplicados con sus titulares antes de migrar.");
                    if (!canonico.equals(original)) cambios.put(filas.getLong("id"),canonico);
                } catch (CamposInvalidosException e) {
                    // Conservar registros históricos inválidos; no inventar números ni eliminar usuarios.
                }
            }
        }
        try (var statement=context.getConnection().prepareStatement("UPDATE usuarios SET telefono=? WHERE id=?")) {
            for(var cambio:cambios.entrySet()) {
                statement.setString(1,cambio.getValue()); statement.setLong(2,cambio.getKey()); statement.addBatch();
            }
            statement.executeBatch();
        }
    }
}
