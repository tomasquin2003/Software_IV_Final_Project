package com.registraduria.votacion.central.candidatos;

import Votacion.CandidatoDuplicadoException;
import Votacion.ConnectionManagerPrx;
import Votacion.ErrorPersistenciaException;
import Votacion.EstadoImportacion;
import Votacion.GestorCandidatos;
import Votacion.ImportacionException;

import com.zeroc.Ice.Current;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * GestorCandidatos - Gestiona el catálogo de candidatos y operaciones de administración.
 * 
 * Maneja alta, baja, importación masiva desde Excel, y validación de unicidad
 * de candidatos en el sistema electoral.
 */
public class GestorCandidatosImpl implements GestorCandidatos {
    private static final Logger logger = LoggerFactory.getLogger(GestorCandidatosImpl.class);
    
    // Dependencias
    private final ConnectionManagerPrx connectionManager;
    
    // Cache de candidatos
    private final ConcurrentHashMap<String, CandidatoInfo> candidatos;
    private final Set<String> candidatosActivos;
    
    // Archivos de persistencia
    private final String archivoCandidatos;
    private final String archivoAuditoria;
    private final String archivoImportacion;
    
    // Locks para operaciones transaccionales
    private final ReentrantReadWriteLock lockCandidatos = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock lockAuditoria = new ReentrantReadWriteLock();
    
    // Executor para operaciones asíncronas
    private final ExecutorService asyncExecutor;
    
    // Estado de importaciones
    private volatile EstadoImportacion estadoImportacionActual = EstadoImportacion.PENDIENTE;
    private volatile String mensajeImportacion = "";
    
    // Métricas
    private volatile AtomicLong totalCandidatos = new AtomicLong(0);
    private volatile AtomicLong totalImportaciones = new AtomicLong(0);
    private volatile AtomicLong totalValidaciones = new AtomicLong(0);
    
    /**
     * Constructor del GestorCandidatos.
     */
    public GestorCandidatosImpl(ConnectionManagerPrx connectionManager, String dataDir) {
        this.connectionManager = connectionManager;
        this.candidatos = new ConcurrentHashMap<>();
        this.candidatosActivos = new HashSet<>();
        this.archivoCandidatos = dataDir + "/Candidatos.db";
        this.archivoAuditoria = dataDir + "/AuditoriaCandidatos.log";
        this.archivoImportacion = dataDir + "/importacionCandidatos.xlsx";
        this.asyncExecutor = Executors.newFixedThreadPool(3);
        
        logger.info("GestorCandidatos inicializado");
        logger.info("ConnectionManager configurado: {}", connectionManager != null ? "OK" : "ERROR");
        logger.info("Archivo candidatos: {}", archivoCandidatos);
        logger.info("Archivo importación: {}", archivoImportacion);
        
        // Inicializar archivos
        inicializarArchivos();
        
        // Cargar candidatos existentes
        cargarCandidatosExistentes();
    }
    
    /**
     * Valida que todos los candidatos en el sistema sean únicos.
     * 
     * @param current Contexto de Ice
     * @return true si todos los candidatos son únicos
     * @throws CandidatoDuplicadoException Si se encuentran candidatos duplicados
     * @throws ErrorPersistenciaException Si hay error de persistencia
     */
    @Override
    public boolean validarCandidatosUnicos(Current current) 
            throws CandidatoDuplicadoException, ErrorPersistenciaException {
        
        logger.info("=== VALIDAR CANDIDATOS UNICOS ===");
        
        lockCandidatos.readLock().lock();
        try {
            totalValidaciones.incrementAndGet();
            
            Set<String> nombresVistos = new HashSet<>();
            Set<String> cedulasVistas = new HashSet<>();
            List<String> duplicados = new ArrayList<>();
            
            // Verificar duplicados por nombre y cédula
            for (CandidatoInfo candidato : candidatos.values()) {
                // Verificar nombre duplicado
                if (nombresVistos.contains(candidato.nombre.toLowerCase())) {
                    duplicados.add(String.format("Nombre duplicado: %s", candidato.nombre));
                } else {
                    nombresVistos.add(candidato.nombre.toLowerCase());
                }
                
                // Verificar cédula duplicada (si tiene)
                if (candidato.cedula != null && !candidato.cedula.isEmpty()) {
                    if (cedulasVistas.contains(candidato.cedula)) {
                        duplicados.add(String.format("Cédula duplicada: %s (%s)", candidato.cedula, candidato.nombre));
                    } else {
                        cedulasVistas.add(candidato.cedula);
                    }
                }
            }
            
            // Escribir auditoría
            escribirAuditoria("VALIDACION_UNICIDAD", "SISTEMA", 
                String.format("Total candidatos: %d, Duplicados encontrados: %d", 
                    candidatos.size(), duplicados.size()));
            
            if (!duplicados.isEmpty()) {
                String mensaje = "Candidatos duplicados encontrados: " + String.join(", ", duplicados);
                logger.warn("Validación de unicidad falló: {}", mensaje);
                throw new CandidatoDuplicadoException("MULTIPLE", mensaje);
            }
            
            logger.info("Validación de unicidad exitosa: {} candidatos únicos", candidatos.size());
            return true;
            
        } catch (CandidatoDuplicadoException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error validando unicidad de candidatos: {}", e.getMessage());
            throw new ErrorPersistenciaException("Error en validación: " + e.getMessage());
        } finally {
            lockCandidatos.readLock().unlock();
        }
    }
    
    /**
     * Abre la interfaz de gestión de candidatos.
     * 
     * @param current Contexto de Ice
     * @throws ErrorPersistenciaException Si hay error de persistencia
     */
    @Override
    public void gestionarCandidatos(Current current) throws ErrorPersistenciaException {
        
        logger.info("=== GESTIONAR CANDIDATOS ===");
        
        try {
            // Mostrar estadísticas actuales
            logger.info("Estadísticas de candidatos:");
            logger.info("- Total candidatos: {}", candidatos.size());
            logger.info("- Candidatos activos: {}", candidatosActivos.size());
            logger.info("- Estado importación: {}", estadoImportacionActual);
            
            // Escribir auditoría
            escribirAuditoria("GESTION_CANDIDATOS", "ADMIN", 
                String.format("Acceso a gestión - Total: %d, Activos: %d", 
                    candidatos.size(), candidatosActivos.size()));
            
            logger.info("Interfaz de gestión de candidatos activada");
            
        } catch (Exception e) {
            logger.error("Error en gestión de candidatos: {}", e.getMessage());
            throw new ErrorPersistenciaException("Error en gestión: " + e.getMessage());
        }
    }
    
    /**
     * Importa candidatos desde archivo Excel.
     * 
     * @param current Contexto de Ice
     * @return Estado de la importación
     * @throws ImportacionException Si hay error en la importación
     * @throws ErrorPersistenciaException Si hay error de persistencia
     */
    @Override
    public EstadoImportacion importacionCandidatosExcel(Current current) 
            throws ImportacionException, ErrorPersistenciaException {
        
        logger.info("=== IMPORTACION CANDIDATOS EXCEL ===");
        logger.info("Archivo: {}", archivoImportacion);
        
        // Cambiar estado a EN PROCESO
        estadoImportacionActual = EstadoImportacion.ENPROCESO;
        mensajeImportacion = "Iniciando importación desde Excel...";
        
        // Ejecutar importación asíncronamente
        Future<EstadoImportacion> futureImportacion = asyncExecutor.submit(() -> {
            try {
                return procesarImportacionExcel();
            } catch (Exception e) {
                logger.error("Error en importación asíncrona: {}", e.getMessage());
                estadoImportacionActual = EstadoImportacion.ERROR;
                mensajeImportacion = "Error: " + e.getMessage();
                return EstadoImportacion.ERROR;
            }
        });
        
        try {
            totalImportaciones.incrementAndGet();
            
            // Escribir auditoría
            escribirAuditoria("IMPORTACION_EXCEL", "ADMIN", 
                String.format("Iniciada importación desde: %s", archivoImportacion));
            
            logger.info("Importación de Excel iniciada en proceso asíncrono");
            return estadoImportacionActual;
            
        } catch (Exception e) {
            estadoImportacionActual = EstadoImportacion.ERROR;
            mensajeImportacion = "Error iniciando importación: " + e.getMessage();
            logger.error("Error iniciando importación: {}", e.getMessage());
            throw new ImportacionException(archivoImportacion, e.getMessage());
        }
    }
    
    /**
     * Guarda candidatos en la base de datos.
     * 
     * @param current Contexto de Ice
     * @throws ErrorPersistenciaException Si hay error de persistencia
     */
    @Override
    public void guardarCandidatos(Current current) throws ErrorPersistenciaException {
        
        logger.info("=== GUARDAR CANDIDATOS ===");
        
        lockCandidatos.readLock().lock();
        try {
            // Persistir vía ConnectionManager
            try {
                connectionManager.guardarCandidatos();
                logger.info("Candidatos guardados en base de datos remota");
            } catch (Exception e) {
                logger.warn("Error guardando en BD remota: {}", e.getMessage());
                // Continuar con persistencia local
            }
            
            // Guardar localmente
            persistirCandidatosLocal();
            
            // Escribir auditoría
            escribirAuditoria("GUARDAR_CANDIDATOS", "SISTEMA", 
                String.format("Guardados %d candidatos", candidatos.size()));
            
            logger.info("Candidatos guardados exitosamente");
            
        } catch (Exception e) {
            logger.error("Error guardando candidatos: {}", e.getMessage());
            throw new ErrorPersistenciaException("Error guardando candidatos: " + e.getMessage());
        } finally {
            lockCandidatos.readLock().unlock();
        }
    }
    
    // === MÉTODOS PÚBLICOS ADICIONALES ===
    
    /**
     * Agrega un nuevo candidato al sistema.
     */
    public void agregarCandidato(String candidatoId, String nombre, String partido, String cargo, String cedula) 
            throws CandidatoDuplicadoException, ErrorPersistenciaException {
        
        lockCandidatos.writeLock().lock();
        try {
            // Verificar duplicados
            if (candidatos.containsKey(candidatoId)) {
                throw new CandidatoDuplicadoException(candidatoId, "Candidato ya existe: " + candidatoId);
            }
            
            // Verificar nombre único
            for (CandidatoInfo candidato : candidatos.values()) {
                if (candidato.nombre.equalsIgnoreCase(nombre)) {
                    throw new CandidatoDuplicadoException(candidatoId, "Nombre ya existe: " + nombre);
                }
            }
            
            // Crear y agregar candidato
            CandidatoInfo nuevoCandidato = new CandidatoInfo(candidatoId, nombre, partido, cargo, cedula, true);
            candidatos.put(candidatoId, nuevoCandidato);
            candidatosActivos.add(candidatoId);
            
            totalCandidatos.incrementAndGet();
            
            // Escribir auditoría
            escribirAuditoria("AGREGAR_CANDIDATO", candidatoId, 
                String.format("Nombre: %s, Partido: %s, Cargo: %s", nombre, partido, cargo));
            
            logger.info("Candidato {} agregado exitosamente: {}", candidatoId, nombre);
            
        } finally {
            lockCandidatos.writeLock().unlock();
        }
    }
    
    /**
     * Obtiene estadísticas de candidatos.
     */
    public String obtenerEstadisticas() {
        return String.format(
            "GestorCandidatos - Total: %d, Activos: %d, Importaciones: %d, Validaciones: %d, Estado: %s",
            candidatos.size(), candidatosActivos.size(), totalImportaciones.get(),
            totalValidaciones.get(), estadoImportacionActual
        );
    }
    
    // === MÉTODOS PRIVADOS ===
    
    /**
     * Procesa la importación desde archivo Excel.
     */
    private EstadoImportacion procesarImportacionExcel() {
        try {
            logger.info("Procesando importación desde Excel: {}", archivoImportacion);
            
            Path archivoPath = Paths.get(archivoImportacion);
            if (!Files.exists(archivoPath)) {
                // Crear archivo de ejemplo si no existe
                crearArchivoEjemplo();
                mensajeImportacion = "Archivo de ejemplo creado. Configure los candidatos y vuelva a importar.";
                return EstadoImportacion.PENDIENTE;
            }
            
            // Simular procesamiento de Excel (en una implementación real usaría Apache POI)
            Thread.sleep(2000); // Simular tiempo de procesamiento
            
            // Por ahora, agregar candidatos de ejemplo
            importarCandidatosEjemplo();
            
            estadoImportacionActual = EstadoImportacion.COMPLETADA;
            mensajeImportacion = String.format("Importación completada. %d candidatos procesados.", candidatos.size());
            
            // Escribir auditoría
            escribirAuditoria("IMPORTACION_COMPLETADA", "EXCEL", 
                String.format("Importados %d candidatos", candidatos.size()));
            
            logger.info("Importación de Excel completada exitosamente");
            return EstadoImportacion.COMPLETADA;
            
        } catch (Exception e) {
            logger.error("Error procesando importación Excel: {}", e.getMessage());
            estadoImportacionActual = EstadoImportacion.ERROR;
            mensajeImportacion = "Error en importación: " + e.getMessage();
            return EstadoImportacion.ERROR;
        }
    }
    
    /**
     * Crea un archivo de ejemplo para importación.
     */
    private void crearArchivoEjemplo() throws IOException {
        String contenidoEjemplo = 
            "# Archivo de ejemplo para importación de candidatos\n" +
            "# Formato: candidatoId|nombre|partido|cargo|cedula\n" +
            "CAND001|Juan Pérez|Partido Liberal|Presidente|12345678\n" +
            "CAND002|María García|Partido Conservador|Presidente|87654321\n" +
            "CAND003|Carlos López|Movimiento Ciudadano|Presidente|11223344\n" +
            "# Agregue más candidatos siguiendo el formato\n";
        
        Files.write(Paths.get(archivoImportacion), contenidoEjemplo.getBytes(), 
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        
        logger.info("Archivo de ejemplo creado: {}", archivoImportacion);
    }
    
    /**
     * Importa candidatos de ejemplo.
     */
    private void importarCandidatosEjemplo() throws Exception {
        lockCandidatos.writeLock().lock();
        try {
            // Leer archivo y procesar líneas
            Path archivoPath = Paths.get(archivoImportacion);
            List<String> lineas = Files.readAllLines(archivoPath);
            
            int importados = 0;
            for (String linea : lineas) {
                if (linea.startsWith("#") || linea.trim().isEmpty()) {
                    continue; // Saltar comentarios y líneas vacías
                }
                
                String[] partes = linea.split("\\|");
                if (partes.length >= 5) {
                    String candidatoId = partes[0].trim();
                    String nombre = partes[1].trim();
                    String partido = partes[2].trim();
                    String cargo = partes[3].trim();
                    String cedula = partes[4].trim();
                    
                    // Verificar si ya existe
                    if (!candidatos.containsKey(candidatoId)) {
                        CandidatoInfo candidato = new CandidatoInfo(candidatoId, nombre, partido, cargo, cedula, true);
                        candidatos.put(candidatoId, candidato);
                        candidatosActivos.add(candidatoId);
                        importados++;
                        
                        logger.debug("Candidato importado: {} - {}", candidatoId, nombre);
                    }
                }
            }
            
            totalCandidatos.addAndGet(importados);
            logger.info("Importados {} candidatos desde archivo", importados);
            
        } finally {
            lockCandidatos.writeLock().unlock();
        }
    }
    
    /**
     * Inicializa archivos de persistencia.
     */
    private void inicializarArchivos() {
        try {
            Path candidatosPath = Paths.get(archivoCandidatos);
            Path auditoriaPath = Paths.get(archivoAuditoria);
            
            // Crear directorios si no existen
            Files.createDirectories(candidatosPath.getParent());
            Files.createDirectories(auditoriaPath.getParent());
            
            // Crear archivos si no existen
            if (!Files.exists(candidatosPath)) {
                Files.createFile(candidatosPath);
                logger.info("Archivo de candidatos creado: {}", archivoCandidatos);
            }
            
            if (!Files.exists(auditoriaPath)) {
                Files.createFile(auditoriaPath);
                logger.info("Archivo de auditoría creado: {}", archivoAuditoria);
            }
            
        } catch (Exception e) {
            logger.error("Error inicializando archivos: {}", e.getMessage());
            throw new RuntimeException("Error inicializando GestorCandidatos", e);
        }
    }
    
    /**
     * Carga candidatos existentes desde archivo local.
     */
    private void cargarCandidatosExistentes() {
        try {
            Path candidatosPath = Paths.get(archivoCandidatos);
            
            if (Files.exists(candidatosPath) && Files.size(candidatosPath) > 0) {
                Files.lines(candidatosPath).forEach(linea -> {
                    try {
                        if (!linea.trim().isEmpty()) {
                            String[] partes = linea.split("\\|", 6);
                            if (partes.length >= 6) {
                                String candidatoId = partes[0];
                                String nombre = partes[1];
                                String partido = partes[2];
                                String cargo = partes[3];
                                String cedula = partes[4];
                                boolean activo = Boolean.parseBoolean(partes[5]);
                                
                                CandidatoInfo candidato = new CandidatoInfo(candidatoId, nombre, partido, cargo, cedula, activo);
                                candidatos.put(candidatoId, candidato);
                                
                                if (activo) {
                                    candidatosActivos.add(candidatoId);
                                }
                            }
                        }
                    } catch (Exception e) {
                        logger.warn("Error cargando línea de candidato: {}", linea);
                    }
                });
                
                logger.info("Cargados {} candidatos desde archivo local", candidatos.size());
            }
            
        } catch (Exception e) {
            logger.error("Error cargando candidatos existentes: {}", e.getMessage());
        }
    }
    
    /**
     * Persiste candidatos en archivo local.
     */
    private void persistirCandidatosLocal() throws IOException {
        List<String> lineas = new ArrayList<>();
        
        for (CandidatoInfo candidato : candidatos.values()) {
            String linea = String.format("%s|%s|%s|%s|%s|%s",
                candidato.candidatoId, candidato.nombre, candidato.partido,
                candidato.cargo, candidato.cedula, candidato.activo);
            lineas.add(linea);
        }
        
        Files.write(Paths.get(archivoCandidatos), lineas, 
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }
    
    /**
     * Escribe un log de auditoría.
     */
    private void escribirAuditoria(String operacion, String candidatoId, String detalles) {
        lockAuditoria.writeLock().lock();
        try {
            String logEntry = String.format("AUDIT|%s|%s|%s|%s%n", 
                Instant.now().toString(), operacion, candidatoId, detalles);
            
            Files.write(Paths.get(archivoAuditoria), logEntry.getBytes(), 
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            
        } catch (Exception e) {
            logger.error("Error escribiendo auditoría: {}", e.getMessage());
        } finally {
            lockAuditoria.writeLock().unlock();
        }
    }
    
    /**
     * Finaliza el GestorCandidatos.
     */
    public void shutdown() {
        logger.info("Finalizando GestorCandidatos...");
        
        if (asyncExecutor != null && !asyncExecutor.isShutdown()) {
            asyncExecutor.shutdown();
        }
        
        // Escribir métricas finales
        escribirAuditoria("SHUTDOWN", "SISTEMA", obtenerEstadisticas());
        
        logger.info("GestorCandidatos finalizado");
        logger.info("Estadísticas finales: {}", obtenerEstadisticas());
    }
    
    // === CLASE INTERNA ===
    
    /**
     * Clase para representar información de un candidato.
     */
    private static class CandidatoInfo {
        final String candidatoId;
        final String nombre;
        final String partido;
        final String cargo;
        final String cedula;
        final boolean activo;
        
        CandidatoInfo(String candidatoId, String nombre, String partido, String cargo, String cedula, boolean activo) {
            this.candidatoId = candidatoId;
            this.nombre = nombre;
            this.partido = partido;
            this.cargo = cargo;
            this.cedula = cedula;
            this.activo = activo;
        }
    }
} 