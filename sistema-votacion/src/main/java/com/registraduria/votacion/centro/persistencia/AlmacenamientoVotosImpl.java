package com.registraduria.votacion.centro.persistencia;

import Votacion.AlmacenamientoVotos;
import Votacion.ErrorPersistenciaException;
import Votacion.EstadoVoto;
import Votacion.Voto;


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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Implementación del servicio AlmacenamientoVotos que maneja la persistencia
 * de votos recibidos en el Centro de Votación.
 */
public class AlmacenamientoVotosImpl implements AlmacenamientoVotos {
    private static final Logger logger = LoggerFactory.getLogger(AlmacenamientoVotosImpl.class);
    
    // Ruta al archivo CSV para almacenamiento de votos
    private final String rutaArchivoVotos;
    
    // Lock para gestionar acceso concurrente al archivo
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    
    // Nombres de las columnas en el archivo CSV
    private static final String[] HEADERS = {"votoId", "candidatoId", "estacionOrigen", "timestamp", "estado"};
    
    /**
     * Constructor que inicializa el componente con la ruta del archivo.
     * 
     * @param rutaArchivoVotos Ruta al archivo CSV para almacenamiento de votos
     */
    public AlmacenamientoVotosImpl(String rutaArchivoVotos) {
        this.rutaArchivoVotos = rutaArchivoVotos;
        
        // Inicializar archivo si no existe
        inicializarArchivoSiNoExiste();
        
        logger.info("AlmacenamientoVotos inicializado. Archivo: {}", rutaArchivoVotos);
    }
    
    /**
     * Inicializa el archivo CSV si no existe.
     */
    private void inicializarArchivoSiNoExiste() {
        Path path = Paths.get(rutaArchivoVotos);
        
        try {
            if (!Files.exists(path.getParent())) {
                Files.createDirectories(path.getParent());
                logger.info("Directorio creado: {}", path.getParent());
            }
            
            if (!Files.exists(path)) {
                try (CSVPrinter printer = new CSVPrinter(
                        new FileWriter(rutaArchivoVotos, StandardCharsets.UTF_8), 
                        CSVFormat.DEFAULT.withHeader(HEADERS))) {
                    // Solo inicializar con headers
                    logger.info("Archivo de votos recibidos creado: {}", rutaArchivoVotos);
                }
            }
        } catch (IOException e) {
            logger.error("Error al inicializar el archivo de votos recibidos", e);
            throw new RuntimeException("Error al inicializar el almacenamiento de votos", e);
        }
    }

    /**
     * Registra un voto recibido en el almacenamiento.
     * 
     * @param votoId ID único del voto
     * @param candidatoId ID del candidato votado
     * @param estacionOrigen Estación de origen del voto
     * @param estado Estado del voto (normalmente RECIBIDO)
     * @param current Contexto de la llamada Ice
     * @throws ErrorPersistenciaException si hay un error al almacenar el voto
     */
    @Override
    public void registrarVotoRecibido(String votoId, String candidatoId, String estacionOrigen, EstadoVoto estado, Current current) 
            throws ErrorPersistenciaException {
        logger.info("Registrando voto recibido. ID: {}, Candidato: {}, Estación: {}, Estado: {}", 
                votoId, candidatoId, estacionOrigen, estado);
        
        lock.writeLock().lock();
        try {
            List<String[]> registros = new ArrayList<>();
            boolean votoExistente = false;
            
            // Leer registros existentes
            if (Files.exists(Paths.get(rutaArchivoVotos)) && Files.size(Paths.get(rutaArchivoVotos)) > 0) {
                try (CSVParser parser = new CSVParser(
                        new FileReader(rutaArchivoVotos, StandardCharsets.UTF_8),
                        CSVFormat.DEFAULT.withHeader(HEADERS).withSkipHeaderRecord(true))) {
                    
                    for (CSVRecord record : parser) {
                        String id = record.get("votoId");
                        
                        if (id.equals(votoId)) {
                            // El voto ya existe, actualizamos su estado y potencialmente su estación de origen si antes era desconocida
                            registros.add(new String[] {
                                id,
                                record.get("candidatoId"),
                                estacionOrigen, // Usar el nuevo valor de estacionOrigen
                                record.get("timestamp"),
                                estado.toString()
                            });
                            votoExistente = true;
                            logger.debug("Voto existente actualizado: {}. Estación: {}", votoId, estacionOrigen);
                        } else {
                            // Mantener registro sin cambios
                            registros.add(new String[] {
                                id,
                                record.get("candidatoId"),
                                record.get("estacionOrigen"),
                                record.get("timestamp"),
                                record.get("estado")
                            });
                        }
                    }
                }
            }
            
            // Si el voto no existe, agregar nuevo registro
            if (!votoExistente) {
                // Timestamp actual
                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                
                registros.add(new String[] {
                    votoId,
                    candidatoId,
                    estacionOrigen, // Usar el parámetro estacionOrigen
                    timestamp,
                    estado.toString()
                });
                
                logger.debug("Nuevo voto registrado: {}. Estación: {}", votoId, estacionOrigen);
            }
            
            // Escribir todos los registros
            try (CSVPrinter printer = new CSVPrinter(
                    new FileWriter(rutaArchivoVotos, StandardCharsets.UTF_8),
                    CSVFormat.DEFAULT.withHeader(HEADERS))) {
                
                for (String[] registro : registros) {
                    printer.printRecord((Object[])registro);
                }
            }
            
            logger.info("Voto recibido registrado correctamente. ID: {}", votoId);
            
        } catch (IOException e) {
            logger.error("Error al registrar voto recibido", e);
            throw new ErrorPersistenciaException("Error al registrar voto recibido: " + e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Marca un voto como procesado cambiando su estado.
     * 
     * @param votoId ID del voto a marcar como procesado
     * @param current Contexto de la llamada Ice
     * @throws ErrorPersistenciaException si hay un error al actualizar el voto
     */
    @Override
    public void marcarVotoProcesado(String votoId, Current current) throws ErrorPersistenciaException {
        logger.info("Marcando voto como procesado. ID: {}", votoId);
        
        lock.writeLock().lock();
        try {
            List<String[]> registros = new ArrayList<>();
            boolean votoEncontrado = false;
            
            if (Files.exists(Paths.get(rutaArchivoVotos)) && Files.size(Paths.get(rutaArchivoVotos)) > 0) {
                try (CSVParser parser = new CSVParser(
                        new FileReader(rutaArchivoVotos, StandardCharsets.UTF_8),
                        CSVFormat.DEFAULT.withHeader(HEADERS).withSkipHeaderRecord(true))) {
                    
                    for (CSVRecord record : parser) {
                        String id = record.get("votoId");
                        
                        if (id.equals(votoId)) {
                            // Actualizar estado a PROCESADO
                            registros.add(new String[] {
                                id,
                                record.get("candidatoId"),
                                record.get("estacionOrigen"),
                                record.get("timestamp"),
                                EstadoVoto.PROCESADO.toString()
                            });
                            votoEncontrado = true;
                            logger.debug("Voto encontrado y marcado como procesado: {}", votoId);
                        } else {
                            // Mantener registro sin cambios
                            registros.add(new String[] {
                                id,
                                record.get("candidatoId"),
                                record.get("estacionOrigen"),
                                record.get("timestamp"),
                                record.get("estado")
                            });
                        }
                    }
                }
            }
            
            if (!votoEncontrado) {
                logger.warn("No se encontró el voto con ID: {}", votoId);
                throw new ErrorPersistenciaException("No se encontró el voto con ID: " + votoId);
            }
            
            // Escribir todos los registros actualizados
            try (CSVPrinter printer = new CSVPrinter(
                    new FileWriter(rutaArchivoVotos, StandardCharsets.UTF_8),
                    CSVFormat.DEFAULT.withHeader(HEADERS))) {
                
                for (String[] registro : registros) {
                    printer.printRecord((Object[])registro);
                }
            }
            
            logger.info("Voto marcado como procesado correctamente. ID: {}", votoId);
            
        } catch (IOException e) {
            logger.error("Error al marcar voto como procesado", e);
            throw new ErrorPersistenciaException("Error al marcar voto como procesado: " + e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Verifica si hay votos pendientes de procesamiento.
     *
     * @param current Contexto de la llamada Ice
     * @return true si hay votos pendientes, false en caso contrario
     * @throws ErrorPersistenciaException si hay un error al leer el almacenamiento
     */
    @Override
    public boolean hayVotosPendientes(Current current) throws ErrorPersistenciaException {
        logger.debug("Verificando si hay votos pendientes");
        lock.readLock().lock();
        try {
            if (Files.exists(Paths.get(rutaArchivoVotos)) && Files.size(Paths.get(rutaArchivoVotos)) > 0) {
                try (CSVParser parser = new CSVParser(
                        new FileReader(rutaArchivoVotos, StandardCharsets.UTF_8),
                        CSVFormat.DEFAULT.withHeader(HEADERS).withSkipHeaderRecord(true))) {
                    for (CSVRecord record : parser) {
                        if (EstadoVoto.PENDIENTE.toString().equalsIgnoreCase(record.get("estado"))) {
                            logger.info("Se encontraron votos pendientes.");
                            return true;
                        }
                    }
                }
            }
            logger.info("No se encontraron votos pendientes.");
            return false;
        } catch (IOException e) {
            logger.error("Error al verificar si hay votos pendientes", e);
            throw new ErrorPersistenciaException("Error al leer el archivo de votos: " + e.getMessage());
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Obtiene un voto pendiente específico por su ID.
     *
     * @param votoId ID del voto a obtener
     * @param current Contexto de la llamada Ice
     * @return El voto pendiente si se encuentra y está en estado PENDIENTE
     * @throws ErrorPersistenciaException si hay un error o no se encuentra el voto
     */
    @Override
    public Voto obtenerVotoPendiente(String votoId, Current current) throws ErrorPersistenciaException {
        logger.info("Intentando obtener voto pendiente con ID: {}", votoId);
        lock.readLock().lock();
        try {
            if (Files.exists(Paths.get(rutaArchivoVotos)) && Files.size(Paths.get(rutaArchivoVotos)) > 0) {
                try (CSVParser parser = new CSVParser(
                        new FileReader(rutaArchivoVotos, StandardCharsets.UTF_8),
                        CSVFormat.DEFAULT.withHeader(HEADERS).withSkipHeaderRecord(true))) {
                    for (CSVRecord record : parser) {
                        if (record.get("votoId").equals(votoId) && 
                            EstadoVoto.PENDIENTE.toString().equalsIgnoreCase(record.get("estado"))) {
                            Voto voto = new Voto();
                            voto.votoId = record.get("votoId");
                            voto.candidatoId = record.get("candidatoId");
                            voto.estacionOrigen = record.get("estacionOrigen");
                            voto.timestamp = record.get("timestamp");
                            logger.info("Voto pendiente encontrado: {}", votoId);
                            return voto;
                        }
                    }
                }
            }
            logger.warn("No se encontró el voto pendiente con ID: {} o no está en estado PENDIENTE", votoId);
            throw new ErrorPersistenciaException("No se encontró el voto pendiente con ID: " + votoId);
        } catch (IOException e) {
            logger.error("Error al obtener voto pendiente", e);
            throw new ErrorPersistenciaException("Error al leer el archivo de votos: " + e.getMessage());
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Obtiene los IDs de todos los votos pendientes, separados por comas.
     *
     * @param current Contexto de la llamada Ice
     * @return Un string con los IDs de los votos pendientes, o string vacío si no hay.
     * @throws ErrorPersistenciaException si hay un error al leer el almacenamiento
     */
    @Override
    public String obtenerIdsVotosPendientes(Current current) throws ErrorPersistenciaException {
        logger.debug("Obteniendo IDs de votos pendientes");
        lock.readLock().lock();
        List<String> idsPendientes = new ArrayList<>();
        try {
            if (Files.exists(Paths.get(rutaArchivoVotos)) && Files.size(Paths.get(rutaArchivoVotos)) > 0) {
                try (CSVParser parser = new CSVParser(
                        new FileReader(rutaArchivoVotos, StandardCharsets.UTF_8),
                        CSVFormat.DEFAULT.withHeader(HEADERS).withSkipHeaderRecord(true))) {
                    for (CSVRecord record : parser) {
                        if (EstadoVoto.PENDIENTE.toString().equalsIgnoreCase(record.get("estado"))) {
                            idsPendientes.add(record.get("votoId"));
                        }
                    }
                }
            }
            if (!idsPendientes.isEmpty()) {
                logger.info("Se encontraron {} IDs de votos pendientes.", idsPendientes.size());
                return String.join(",", idsPendientes);
            } else {
                logger.info("No se encontraron IDs de votos pendientes.");
                return "";
            }
        } catch (IOException e) {
            logger.error("Error al obtener IDs de votos pendientes", e);
            throw new ErrorPersistenciaException("Error al leer el archivo de votos: " + e.getMessage());
        } finally {
            lock.readLock().unlock();
        }
    }
}