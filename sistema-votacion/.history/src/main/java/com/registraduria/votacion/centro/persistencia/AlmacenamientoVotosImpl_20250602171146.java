package com.registraduria.votacion.centro.persistencia;

import Votacion.AlmacenamientoVotos;
import Votacion.ErrorPersistenciaException;
import Votacion.EstadoVoto;
import Votacion.Voto;
import Votacion.VotoSeq;
import Votacion.VotoSeqHelper;

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
     * @param estado Estado del voto (normalmente RECIBIDO)
     * @param current Contexto de la llamada Ice
     * @throws ErrorPersistenciaException si hay un error al almacenar el voto
     */
    @Override
    public void registrarVotoRecibido(String votoId, String candidatoId, EstadoVoto estado, Current current) 
            throws ErrorPersistenciaException {
        logger.info("Registrando voto recibido. ID: {}, Candidato: {}, Estado: {}", 
                votoId, candidatoId, estado);
        
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
                            // El voto ya existe, actualizamos su estado
                            registros.add(new String[] {
                                id,
                                record.get("candidatoId"),
                                record.get("estacionOrigen"),
                                record.get("timestamp"),
                                estado.toString()
                            });
                            votoExistente = true;
                            logger.debug("Voto existente actualizado: {}", votoId);
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
                
                // Estación origen desconocida en este punto, se completará cuando se reciba el voto completo
                registros.add(new String[] {
                    votoId,
                    candidatoId,
                    "desconocido", // estación origen
                    timestamp,
                    estado.toString()
                });
                
                logger.debug("Nuevo voto registrado: {}", votoId);
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
     * Recupera todos los votos en estado recibido para procesamiento.
     * 
     * @param current Contexto de la llamada Ice
     * @return Secuencia de votos recibidos pendientes de procesar
     * @throws ErrorPersistenciaException si hay un error al recuperar los votos
     */
    @Override
public Voto[] recuperarVotosPendientes(Current current) throws ErrorPersistenciaException {
    logger.info("Recuperando votos pendientes");
    
    List<Voto> votosPendientes = new ArrayList<>();
        
        lock.readLock().lock();
        try {
            if (Files.exists(Paths.get(rutaArchivoVotos)) && Files.size(Paths.get(rutaArchivoVotos)) > 0) {
                try (CSVParser parser = new CSVParser(
                        new FileReader(rutaArchivoVotos, StandardCharsets.UTF_8),
                        CSVFormat.DEFAULT.withHeader(HEADERS).withSkipHeaderRecord(true))) {
                    
                    for (CSVRecord record : parser) {
                        String estado = record.get("estado");
                        
                        // Solo recuperar votos en estado RECIBIDO
                        if (estado.equals(EstadoVoto.RECIBIDO.toString())) {
                            Voto voto = new Voto(
                                record.get("votoId"),
                                record.get("candidatoId"),
                                record.get("estacionOrigen"),
                                record.get("timestamp")
                            );
                            votosPendientes.add(voto);
                        }
                    }
                }
            }
            
            logger.info("Recuperados {} votos pendientes de procesar", votosPendientes.size());
            
            // Convertir lista a VotoSeq
            return VotoSeqHelper.convert(votosPendientes.toArray(new Voto[0]));
            
        } catch (IOException e) {
            logger.error("Error al recuperar votos pendientes", e);
            throw new ErrorPersistenciaException("Error al recuperar votos pendientes: " + e.getMessage());
        } finally {
            lock.readLock().unlock();
        }
    }
}