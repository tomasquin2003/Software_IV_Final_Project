package com.registraduria.votacion.estacion.controller;

import Votacion.ControllerEstacion;
import Votacion.SistemaMonitorizacionPrx;
import Votacion.VotanteNoAutorizadoException;
import com.zeroc.Ice.Current;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * Implementación del servicio ControllerEstacion que proporciona el punto de entrada
 * para la autenticación de votantes.
 */
public class ControllerEstacionImpl implements ControllerEstacion {
    private static final Logger logger = LoggerFactory.getLogger(ControllerEstacionImpl.class);    // Proxy para acceder al servicio SistemaMonitorizacion
    private final SistemaMonitorizacionPrx sistemaMonitorizacion;
    
    // Ruta al archivo CSV de votos transitorios para verificar cedulas
    private final String rutaVotosTransitorios;
    
    // Conjunto para almacenar las cedulas que ya han votado (cache en memoria)
    private final Set<String> cedulasVotadas = new HashSet<>();    /**
     * Constructor que recibe el proxy para el SistemaMonitorizacion.
     * 
     * @param sistemaMonitorizacion Proxy para el servicio SistemaMonitorizacion
     * @param rutaVotosTransitorios Ruta al archivo CSV de votos transitorios
     */
    public ControllerEstacionImpl(SistemaMonitorizacionPrx sistemaMonitorizacion, String rutaVotosTransitorios) {
        this.sistemaMonitorizacion = sistemaMonitorizacion;
        this.rutaVotosTransitorios = rutaVotosTransitorios;
        
        // Cargar cedulas ya votadas desde el archivo de votos transitorios
        cargarCedulasVotadas();
        
        logger.info("ControllerEstacion inicializado con SistemaMonitorizacion y {} cedulas votadas cargadas", 
                cedulasVotadas.size());
    }/**
     * Autentica a un votante para permitirle emitir su voto.
     * Ahora verifica persistentemente si el votante ya ha votado.
     * 
     * @param cedula Cedula del votante a autenticar
     * @param current Contexto de la llamada Ice
     * @return true si el votante está autorizado para votar
     * @throws VotanteNoAutorizadoException si el votante no está autorizado o ya ha votado
     */
    @Override
    public boolean autenticarVotante(String cedula, Current current) throws VotanteNoAutorizadoException {
        logger.info("Solicitando autenticacion para cedula: {}", cedula);
          // VERIFICACION CRITICA! - Verificar si el votante ya ha votado (persistente)
        synchronized (cedulasVotadas) {
            if (cedulasVotadas.contains(cedula)) {
                logger.warn("VOTO DUPLICADO DETECTADO: Cedula {} ya ha votado anteriormente", cedula);
                throw new VotanteNoAutorizadoException(cedula, "Este votante ya ha emitido su voto anteriormente");
            }
        }
        
        try {
            // Iniciar el proceso de verificación a través del SistemaMonitorizacion
            boolean resultado = sistemaMonitorizacion.iniciarVerificacion(cedula);
              if (resultado) {
                logger.info("Votante con cedula {} autenticado exitosamente", cedula);
                  // CAMBIO CRITICO! - Registrar inmediatamente la cedula en memoria
                // La cedula se guardara automaticamente en el archivo cuando se almacene el voto
                synchronized (cedulasVotadas) {
                    cedulasVotadas.add(cedula);
                }
                
                return true;
            } else {
                // Este caso no debería ocurrir según la implementación actual
                logger.warn("Autenticacion fallida para cedula: {}", cedula);
                return false;
            }        } catch (VotanteNoAutorizadoException e) {
            logger.warn("Excepcion durante la autenticacion: {}", e.mensaje);
            throw e; // Re-lanzar la excepción para que la maneje la UI
        } catch (Exception e) {            logger.error("Error durante la autenticacion del votante", e);
            throw new VotanteNoAutorizadoException(cedula, "Error en el sistema de autenticacion: " + e.getMessage());        }
    }
    
    /**
     * Carga las cedulas ya votadas desde el archivo CSV de votos transitorios.
     */
    private void cargarCedulasVotadas() {
        logger.info("Cargando cedulas votadas desde: {}", rutaVotosTransitorios);
        
        try {
            Path path = Paths.get(rutaVotosTransitorios);
            if (!Files.exists(path) || Files.size(path) == 0) {
                logger.info("Archivo de votos transitorios vacio o no existe");
                return;
            }
            
            try (FileReader reader = new FileReader(rutaVotosTransitorios, StandardCharsets.UTF_8);
                 CSVParser csvParser = new CSVParser(reader, CSVFormat.DEFAULT
                         .withFirstRecordAsHeader()
                         .withIgnoreHeaderCase()
                         .withTrim())) {
                
                for (CSVRecord csvRecord : csvParser) {
                    String cedula = csvRecord.get("cedulaVotante");
                    if (cedula != null && !cedula.trim().isEmpty() && !cedula.equals("DESCONOCIDA")) {
                        cedulasVotadas.add(cedula);
                    }
                }
                
                logger.info("Se cargaron {} cedulas votadas", cedulasVotadas.size());
                
            }
        } catch (IOException e) {
            logger.error("Error al cargar cedulas votadas", e);
            // No lanzamos excepcion, simplemente empezamos con conjunto vacio
        }
    }
}