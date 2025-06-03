package com.registraduria.votacion.estacion.persistencia;

import Votacion.AlmacenamientoTransitorio;
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
 * Implementación del servicio AlmacenamientoTransitorio que maneja la persistencia
 * temporal de votos en la Estación de Votación.
 */
public class AlmacenamientoTransitorioImpl implements AlmacenamientoTransitorio {
    private static final Logger logger = LoggerFactory.getLogger(AlmacenamientoTransitorioImpl.class);
    
    // Ruta al archivo CSV para almacenamiento transitorio
    private final String rutaArchivoVotos;
    
    // ID de la estación de votación para identificar el origen de los votos
    private final String estacionId;
    
    // Lock para gestionar acceso concurrente al archivo
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    
    // Nombres de las columnas en el archivo CSV
    private static final String[] HEADERS = {"votoId", "candidatoId", "estacionOrigen", "timestamp", "estado"};
    
    /**
     * Constructor que inicializa el componente con la ruta del archivo y el ID de la estación.
     * 
     * @param rutaArchivoVotos Ruta al archivo CSV para almacenamiento transitorio
     * @param estacionId ID de la estación de votación
     */
    public AlmacenamientoTransitorioImpl(String rutaArchivoVotos, String estacionId) {
        this.rutaArchivoVotos = rutaArchivoVotos;
        this.estacionId = estacionId;
        
        // Inicializar archivo si no existe
        inicializarArchivoSiNoExiste();
        
        logger.info("AlmacenamientoTransitorio inicializado. Archivo: {}, Estación: {}", 
                rutaArchivoVotos, estacionId);
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
                    logger.info("Archivo de votos transitorios creado: {}", rutaArchivoVotos);
                }
            }
        } catch (IOException e) {
            logger.error("Error al inicializar el archivo de votos transitorios", e);
            throw new RuntimeException("Error al inicializar el almacenamiento transitorio", e);
        }
    }

    /**
     * Almacena un voto en estado transitorio.
     * 
     * @param votoId ID único del voto
     * @param candidatoId ID del candidato votado
     * @param estado Estado inicial del voto (normalmente PENDIENTE)
     * @param current Contexto de la llamada Ice
     * @throws ErrorPersistenciaException si hay un error al almacenar el voto
     */
    @Override
    public void almacenarVotoTransitorio(String votoId, String candidatoId, EstadoVoto estado, Current current) 
            throws ErrorPersistenciaException {
        logger.info("Almacenando voto transitorio. ID: {}, Candidato: {}, Estado: {}", 
                votoId, candidatoId, estado);
        
        lock.writeLock().lock();
        try {
            List<String[]> registros = new ArrayList<>();
            
            // Leer registros existentes
            if (Files.exists(Paths.get(rutaArchivoVotos)) && Files.size(Paths.get(rutaArchivoVotos)) > 0) {
                try (CSVParser parser = new CSVParser(
                        new FileReader(rutaArchivoVotos, StandardCharsets.UTF_8),
                        CSVFormat.DEFAULT.withHeader(HEADERS).withSkipHeaderRecord(true))) {
                    
                    for (CSVRecord record : parser) {
                        // Verificar si el voto ya existe
                        if (record.get("votoId").equals(votoId)) {
                            throw new ErrorPersistenciaException("El voto con ID " + votoId + " ya existe");
                        }
                        
                        registros.add(new String[] {
                            record.get("votoId"),
                            record.get("candidatoId"),
                            record.get("estacionOrigen"),
                            record.get("timestamp"),
                            record.get("estado")
                        });
                    }
                }
            }
            
            // Timestamp actual
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            
            // Agregar nuevo registro
            registros.add(new String[] {
                votoId,
                candidatoId,
                estacionId,
                timestamp,
                estado.toString()
            });
            
            // Escribir todos los registros
            try (CSVPrinter printer = new CSVPrinter(
                    new FileWriter(rutaArchivoVotos, StandardCharsets.UTF_8),
                    CSVFormat.DEFAULT.withHeader(HEADERS))) {
                
                for (String[] registro : registros) {
                    printer.printRecord((Object[])registro);
                }
            }
            
            logger.info("Voto transitorio almacenado correctamente. ID: {}", votoId);
            
        } catch (IOException e) {
            logger.error("Error al almacenar voto transitorio", e);
            throw new ErrorPersistenciaException("Error al almacenar voto transitorio: " + e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Recupera todos los votos en estado pendiente para reintento.
     * 
     * @param current Contexto de la llamada Ice
     * @return Secuencia de votos pendientes
     * @throws ErrorPersistenciaException si hay un error al recuperar los votos
     */
    @Override
    public VotoSeq recuperarVotosPendientes(Current current) throws ErrorPersistenciaException {
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
                        
                        // Solo recuperar votos PENDIENTES
                        if (estado.equals(EstadoVoto.PENDIENTE.toString())) {
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
            
            logger.info("Recuperados {} votos pendientes", votosPendientes.size());
            
            // Convertir lista a VotoSeq
            return VotoSeqHelper.convert(votosPendientes.toArray(new Voto[0]));
            
        } catch (IOException e) {
            logger.error("Error al recuperar votos pendientes", e);
            throw new ErrorPersistenciaException("Error al recuperar votos pendientes: " + e.getMessage());
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Marca un voto como confirmado cambiando su estado.
     * 
     * @param votoId ID del voto a confirmar
     * @param current Contexto de la llamada Ice
     * @throws ErrorPersistenciaException si hay un error al actualizar el voto
     */
    @Override
    public void marcarVotoConfirmado(String votoId, Current current) throws ErrorPersistenciaException {
        logger.info("Marcando voto como confirmado. ID: {}", votoId);
        
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
                            logger.debug("Voto encontrado y actualizado: {}", votoId);
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
            
            logger.info("Voto marcado como confirmado correctamente. ID: {}", votoId);
            
        } catch (IOException e) {
            logger.error("Error al marcar voto como confirmado", e);
            throw new ErrorPersistenciaException("Error al marcar voto como confirmado: " + e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }
}