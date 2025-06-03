package com.registraduria.votacion.centro.procesamiento;

import Votacion.MotorEmisionVotos;
import com.zeroc.Ice.Current;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Implementación del servicio MotorEmisionVotos que procesa los votos validados
 * y mantiene el conteo de resultados.
 */
public class MotorEmisionVotosImpl implements MotorEmisionVotos {
    private static final Logger logger = LoggerFactory.getLogger(MotorEmisionVotosImpl.class);
    
    // Mapa para contar votos por candidato
    private final Map<String, Integer> conteoVotos = new ConcurrentHashMap<>();
    
    // Ruta al archivo CSV de candidatos
    private final String rutaArchivoCandidatos;
    
    // Ruta al archivo CSV de resultados
    private final String rutaArchivoResultados;
    
    // Lock para gestionar acceso concurrente al archivo de resultados
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    
    // Mapa para almacenar nombres de candidatos
    private final Map<String, String> nombresCandidatos = new HashMap<>();
    
    /**
     * Constructor que inicializa el motor con las rutas de archivos.
     * 
     * @param rutaArchivoCandidatos Ruta al archivo CSV de candidatos
     * @param rutaArchivoResultados Ruta al archivo CSV de resultados
     */
    public MotorEmisionVotosImpl(String rutaArchivoCandidatos, String rutaArchivoResultados) {
        this.rutaArchivoCandidatos = rutaArchivoCandidatos;
        this.rutaArchivoResultados = rutaArchivoResultados;
        
        // Cargar información de candidatos
        cargarCandidatos();
        
        // Inicializar conteo de votos desde archivo si existe
        inicializarConteoVotos();
        
        logger.info("MotorEmisionVotos inicializado con {} candidatos", nombresCandidatos.size());
    }
    
    /**
     * Carga la información de candidatos desde el archivo CSV.
     */
    private void cargarCandidatos() {
        logger.info("Cargando candidatos desde: {}", rutaArchivoCandidatos);
        
        try (FileReader reader = new FileReader(rutaArchivoCandidatos, StandardCharsets.UTF_8);
             CSVParser csvParser = new CSVParser(reader, CSVFormat.DEFAULT
                     .withFirstRecordAsHeader()
                     .withIgnoreHeaderCase()
                     .withTrim())) {
            
            for (CSVRecord csvRecord : csvParser) {
                String id = csvRecord.get("id");
                String nombre = csvRecord.get("nombre");
                String partido = csvRecord.get("partido");
                
                nombresCandidatos.put(id, nombre + " (" + partido + ")");
                
                // Inicializar conteo en 0 para cada candidato
                conteoVotos.put(id, 0);
                
                logger.debug("Candidato cargado: {} - {}", id, nombre);
            }
            
            logger.info("Se cargaron {} candidatos", nombresCandidatos.size());
            
        } catch (IOException e) {
            logger.error("Error al cargar el archivo de candidatos", e);
            throw new RuntimeException("No se pudo cargar el archivo de candidatos: " + e.getMessage(), e);
        }
    }
    
    /**
     * Inicializa el conteo de votos desde el archivo de resultados si existe.
     */
    private void inicializarConteoVotos() {
        Path path = Paths.get(rutaArchivoResultados);
        
        if (!Files.exists(path)) {
            // Crear archivo de resultados con conteo inicial en cero
            guardarResultados();
            return;
        }
        
        logger.info("Cargando conteo de votos desde: {}", rutaArchivoResultados);
        
        try (FileReader reader = new FileReader(rutaArchivoResultados, StandardCharsets.UTF_8);
             CSVParser csvParser = new CSVParser(reader, CSVFormat.DEFAULT
                     .withFirstRecordAsHeader()
                     .withIgnoreHeaderCase()
                     .withTrim())) {
            
            for (CSVRecord csvRecord : csvParser) {
                String candidatoId = csvRecord.get("candidatoId");
                int conteo = Integer.parseInt(csvRecord.get("conteo"));
                
                conteoVotos.put(candidatoId, conteo);
                
                logger.debug("Conteo cargado: Candidato {}, Votos {}", candidatoId, conteo);
            }
            
            logger.info("Se cargó el conteo de votos para {} candidatos", conteoVotos.size());
            
        } catch (IOException e) {
            logger.warn("No se pudo cargar el archivo de resultados: {}", e.getMessage());
            logger.info("Se continuará con un conteo inicial en cero");
            // Crear archivo de resultados con conteo inicial en cero
            guardarResultados();
        }
    }
    
    /**
     * Guarda los resultados actuales en el archivo CSV.
     */
    private void guardarResultados() {
        logger.info("Guardando resultados en: {}", rutaArchivoResultados);
        
        lock.writeLock().lock();
        try {
            // Asegurar que el directorio exista
            Path path = Paths.get(rutaArchivoResultados);
            if (!Files.exists(path.getParent())) {
                Files.createDirectories(path.getParent());
                logger.info("Directorio creado: {}", path.getParent());
            }
            
            // Timestamp para el archivo
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            
            // Escribir resultados al archivo
            try (CSVPrinter printer = new CSVPrinter(
                    new FileWriter(rutaArchivoResultados, StandardCharsets.UTF_8),
                    CSVFormat.DEFAULT.withHeader("candidatoId", "nombre", "conteo", "ultimaActualizacion"))) {
                
                for (Map.Entry<String, Integer> entry : conteoVotos.entrySet()) {
                    String candidatoId = entry.getKey();
                    int conteo = entry.getValue();
                    String nombre = nombresCandidatos.getOrDefault(candidatoId, "Desconocido");
                    
                    printer.printRecord(candidatoId, nombre, conteo, timestamp);
                }
            }
            
            logger.info("Resultados guardados correctamente");
            
        } catch (IOException e) {
            logger.error("Error al guardar resultados", e);
            throw new RuntimeException("Error al guardar resultados: " + e.getMessage(), e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Procesa un voto validado incrementando el conteo del candidato.
     * 
     * @param candidatoId ID del candidato votado
     * @param current Contexto de la llamada Ice
     */
    @Override
    public void procesarVotoValidado(String candidatoId, Current current) {
        logger.info("Procesando voto validado para candidato: {}", candidatoId);
        
        // Verificar que el candidato exista
        if (!conteoVotos.containsKey(candidatoId)) {
            logger.warn("Candidato con ID {} no encontrado. Voto ignorado.", candidatoId);
            return;
        }
        
        // Incrementar conteo atómicamente
        int nuevoConteo = conteoVotos.compute(candidatoId, (k, v) -> v + 1);
        
        logger.info("Conteo actualizado para candidato {} ({}): {}",
                candidatoId, nombresCandidatos.getOrDefault(candidatoId, "Desconocido"), nuevoConteo);
        
        // Guardar resultados actualizados
        guardarResultados();
        
        // Aquí se podría agregar lógica adicional, como notificaciones en tiempo real
    }
    
    /**
     * Obtiene el conteo actual de votos para un candidato.
     * 
     * @param candidatoId ID del candidato
     * @return Número de votos para el candidato
     */
    public int obtenerConteo(String candidatoId) {
        return conteoVotos.getOrDefault(candidatoId, 0);
    }
    
    /**
     * Obtiene el mapa completo de resultados.
     * 
     * @return Mapa con candidatos y sus conteos
     */
    public Map<String, Integer> obtenerResultados() {
        return new HashMap<>(conteoVotos);
    }

    /**
 * Obtiene el nombre de un candidato a partir de su ID.
 * 
 * @param candidatoId ID del candidato
 * @return Nombre del candidato o "Desconocido" si no se encuentra
 */
public String getNombreCandidato(String candidatoId) {
    return nombresCandidatos.getOrDefault(candidatoId, "Desconocido");
}
}