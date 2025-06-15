package com.registraduria.votacion.centro.validacion;

import Votacion.ValidadorDeVotos;
import com.zeroc.Ice.Current;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * Implementación del servicio ValidadorDeVotos que verifica la unicidad
 * de los votos recibidos para evitar duplicados.
 */
public class ValidadorDeVotosImpl implements ValidadorDeVotos {
    private static final Logger logger = LoggerFactory.getLogger(ValidadorDeVotosImpl.class);
      // Conjunto para almacenar los IDs de votos ya procesados
    private final Set<String> votosRecibidos = Collections.synchronizedSet(new HashSet<>());
    
    // Conjunto para almacenar las cédulas que ya han votado
    private final Set<String> cedulasQueVotaron = Collections.synchronizedSet(new HashSet<>());
    
    // Lock para acceso concurrente al conjunto de votos
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    
    // Ruta al archivo CSV de votos recibidos
    private final String rutaArchivoVotos;
    
    /**
     * Constructor que inicializa el validador con la ruta al archivo de votos.
     * 
     * @param rutaArchivoVotos Ruta al archivo CSV de votos recibidos
     */
    public ValidadorDeVotosImpl(String rutaArchivoVotos) {
        this.rutaArchivoVotos = rutaArchivoVotos;
          // Cargar votos recibidos desde el archivo
        cargarVotosRecibidos();
        
        logger.info("ValidadorDeVotos inicializado con {} votos registrados y {} cédulas que votaron", 
                    votosRecibidos.size(), cedulasQueVotaron.size());
    }
    
    /**
     * Carga los IDs de votos ya procesados desde el archivo CSV.
     */
    private void cargarVotosRecibidos() {
        logger.info("Cargando votos recibidos desde: {}", rutaArchivoVotos);
        
        lock.writeLock().lock();
        try {
            try (FileReader reader = new FileReader(rutaArchivoVotos, StandardCharsets.UTF_8);
                 CSVParser csvParser = new CSVParser(reader, CSVFormat.DEFAULT
                         .withFirstRecordAsHeader()
                         .withIgnoreHeaderCase()
                         .withTrim())) {
                  for (CSVRecord csvRecord : csvParser) {
                    String votoId = csvRecord.get("votoId");
                    votosRecibidos.add(votoId);
                    
                    // También cargar la cédula si está disponible
                    try {
                        String cedulaVotante = csvRecord.get("cedulaVotante");
                        if (cedulaVotante != null && !cedulaVotante.isEmpty() && !cedulaVotante.equals("DESCONOCIDA")) {
                            cedulasQueVotaron.add(cedulaVotante);
                        }
                    } catch (IllegalArgumentException e) {
                        // La columna cedulaVotante no existe en registros antiguos, se ignora
                        logger.debug("Columna cedulaVotante no encontrada en registro con votoId: {}", votoId);
                    }
                }
                
                logger.info("Se cargaron {} votos recibidos y {} cédulas que votaron", 
                           votosRecibidos.size(), cedulasQueVotaron.size());
                
            } catch (IOException e) {
                logger.warn("No se pudo cargar el archivo de votos recibidos: {}", e.getMessage());
                logger.info("Se continuará con un conjunto de votos vacío");
                // No lanzamos excepción, simplemente empezamos con un conjunto vacío
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Verifica si un voto es único (no ha sido procesado anteriormente).
     * 
     * @param votoId ID del voto a verificar
     * @param current Contexto de la llamada Ice
     * @return true si el voto es único, false si ya existe
     */
    @Override
    public boolean validarVotoUnico(String votoId, Current current) {
        logger.info("Validando unicidad del voto con ID: {}", votoId);
        
        lock.readLock().lock();
        try {
            boolean esUnico = !votosRecibidos.contains(votoId);
            
            if (esUnico) {
                logger.info("Voto con ID {} es único", votoId);
            } else {
                logger.warn("Voto con ID {} ya existe", votoId);
            }
            
            return esUnico;
            
        } finally {
            lock.readLock().unlock();
        }
    }
      /**
     * Verifica si una cédula ya ha sido usada para votar.
     * 
     * @param cedulaVotante Cédula del votante a verificar
     * @return true si la cédula no ha votado, false si ya votó
     */
    public boolean validarCedulaUnica(String cedulaVotante) {
        logger.info("Validando unicidad de la cédula: {}", cedulaVotante);
        
        if (cedulaVotante == null || cedulaVotante.isEmpty() || cedulaVotante.equals("DESCONOCIDA")) {
            logger.warn("Cédula inválida o desconocida: {}", cedulaVotante);
            return false; // Rechazar votos sin cédula válida
        }
        
        lock.readLock().lock();
        try {
            boolean esUnica = !cedulasQueVotaron.contains(cedulaVotante);
            
            if (esUnica) {
                logger.info("Cédula {} no ha votado anteriormente", cedulaVotante);
            } else {
                logger.warn("Cédula {} ya ha sido usada para votar", cedulaVotante);
            }
            
            return esUnica;
            
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Registra un voto como procesado para evitar duplicados.
     * Este método se llama internamente desde GestorRecepcionVotos.
     * 
     * @param votoId ID del voto a registrar
     * @param cedulaVotante Cédula del votante que emitió el voto
     */
    public void registrarVotoProcesado(String votoId, String cedulaVotante) {
        logger.info("Registrando voto procesado con ID: {} y cédula: {}", votoId, cedulaVotante);
        
        lock.writeLock().lock();
        try {
            votosRecibidos.add(votoId);
            
            if (cedulaVotante != null && !cedulaVotante.isEmpty() && !cedulaVotante.equals("DESCONOCIDA")) {
                cedulasQueVotaron.add(cedulaVotante);
                logger.debug("Cédula {} registrada como votante", cedulaVotante);
            }
            
            logger.debug("Voto con ID {} registrado como procesado", votoId);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Registra un voto como procesado para evitar duplicados (método legacy).
     * Este método se mantiene para compatibilidad con código existente.
     * 
     * @param votoId ID del voto a registrar
     */
    public void registrarVotoProcesado(String votoId) {
        registrarVotoProcesado(votoId, null);
    }
}