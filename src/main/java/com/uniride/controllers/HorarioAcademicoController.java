package com.uniride.controllers;

import com.uniride.dto.responses.ArchivoRespuesta;
import com.uniride.dto.requests.CursoRequest;
import com.uniride.dto.responses.CursoRespuesta;
import com.uniride.services.HorarioAcademicoService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/perfil")
public class HorarioAcademicoController {

    private final HorarioAcademicoService horarioAcademicoService;

    public HorarioAcademicoController(HorarioAcademicoService horarioAcademicoService) {
        this.horarioAcademicoService = horarioAcademicoService;
    }

    @PostMapping("/cursos")
    public ResponseEntity<List<CursoRespuesta>> guardarCursos(
            @Valid @RequestBody List<CursoRequest> requests) {
        return ResponseEntity.status(HttpStatus.CREATED).body(horarioAcademicoService.guardarCursos(requests));
    }

    @GetMapping("/cursos")
    public ResponseEntity<Page<CursoRespuesta>> listarCursos(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by("dia").ascending());
        return ResponseEntity.ok(horarioAcademicoService.listarCursos(pageable));
    }

    @DeleteMapping("/cursos/{id}")
    public ResponseEntity<Void> eliminarCurso(@PathVariable Long id) {
        horarioAcademicoService.eliminarCurso(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/horarios/archivo")
    public ResponseEntity<ArchivoRespuesta> subirArchivo(@RequestParam("archivo") MultipartFile archivo) {
        return ResponseEntity.status(HttpStatus.CREATED).body(horarioAcademicoService.subirArchivo(archivo));
    }
}
