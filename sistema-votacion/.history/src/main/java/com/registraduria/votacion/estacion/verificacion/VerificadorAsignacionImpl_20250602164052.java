package com.registraduria.votacion.estacion.verificacion;

import Votacion.VerificadorAsignacion;
import Votacion.VotanteNoAutorizadoException;
import com.zeroc.Ice.Current;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Implementación del servicio VerificadorAsignacion que valida si un votante
 * está autorizado para votar en una mesa específica.
 */
public class VerificadorAsignacionImpl implements VerificadorAsignacion {
    private static final Logger logger = LoggerFactory.getLogger(VerificadorAsignacionImpl.class);
    
    // Almacena las cédulas autorizadas y sus mesas asignadas
    private final Map<String, String> cedulasAutorizadas = new HashMap<>();
    
    /**
     * Constructor que carga las cédulas autorizadas desde un archivo CSV.
     * 
     * @param rutaArchivoCedulas Ruta al archivo CSV de cédulas autorizadas
     */
    public VerificadorAsignacionImpl(String rutaArchivoCedulas) {
        cargarCedulasAutorizadas(rutaArchivoCedulas);
    }
    
    /**
     * Carga las cédulas autorizadas desde el archivo CSV.
     * 
     * @param rutaArchivoCedulas Ruta al archivo CSV
     */
    private void cargarCedulasAutorizadas(String rutaArchivoCedulas) {
        logger.info("Cargando cédulas autorizadas desde: {}", rutaArchivoCedulas);
        
        try (FileReader reader = new FileReader(rutaArchivoCedulas, StandardCharsets.UTF_8);
             CSVParser csvParser = new CSVParser(reader, CSVFormat.DEFAULT
                     .withFirstRecordAsHeader()
                     .withIgnoreHeaderCase()
                     .withTrim())) {
            
            for (CSVRecord csvRecord : csvParser) {
                String cedula = csvRecord.get("cedula");
                String mesaAsignada = csvRecord.get("mesa_asignada");
                String nombre = csvRecord.get("nombre");
                
                cedulasAutorizadas.put(cedula, mesaAsignada);
                logger.debug("Cédula autorizada: {}, Mesa: {}, Nombre: {}", cedula, mesaAsignada, nombre);
            }
            
            logger.info("Se cargaron {} cédulas autorizadas", cedulasAutorizadas.size());
            
        } catch (IOException e) {
            logger.error("Error al cargar el archivo de cédulas autorizadas", e);
            throw new RuntimeException("No se pudo cargar el archivo de cédulas autorizadas: " + e.getMessage(), e);
        }
    }

    /**
     * Valida si una cédula está autorizada para votar.
     * 
     * @param cedula Cédula del votante a verificar
     * @param current Contexto de la llamada Ice
     * @return true si la cédula está autorizada
     * @throws VotanteNoAutorizadoException si la cédula no está autorizada
     */
    @Override
    public boolean validarMesa(String cedula, Current current) throws VotanteNoAutorizadoException {
        logger.info("Verificando autorización para cédula: {}", cedula);
        
        if (!cedulasAutorizadas.containsKey(cedula)) {
            logger.warn("Cédula no autorizada: {}", cedula);
            throw new VotanteNoAutorizadoException(cedula, "Votante no autorizado para esta mesa");
        }
        
        String mesaAsignada = cedulasAutorizadas.get(cedula);
        logger.info("Cédula {} autorizada para mesa {}", cedula, mesaAsignada);
        
        return true;
    }
}