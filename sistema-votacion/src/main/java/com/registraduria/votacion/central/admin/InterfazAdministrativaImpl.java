package com.registraduria.votacion.central.admin;

import Votacion.CandidatoDuplicadoException;
import Votacion.ErrorPersistenciaException;
import Votacion.EstadoImportacion;
import Votacion.ImportacionException;
import Votacion.InterfazAdministrativa;
import Votacion.MesaNoExisteException;

import com.registraduria.votacion.central.candidatos.GestorCandidatosImpl;
import com.registraduria.votacion.central.mesa.AdministradorMesaImpl;

import com.zeroc.Ice.Current;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * InterfazAdministrativa - Panel de control para operatoria central.
 * 
 * Integra funcionalidades de GestorCandidatos y AdministradorMesa
 * proporcionando una interfaz unificada para administración.
 */
public class InterfazAdministrativaImpl implements InterfazAdministrativa {
    private static final Logger logger = LoggerFactory.getLogger(InterfazAdministrativaImpl.class);
    
    // Componentes gestionados
    private final GestorCandidatosImpl gestorCandidatos;
    private final AdministradorMesaImpl administradorMesa;
    
    // Archivo de auditoría
    private final String archivoAuditoriaAdmin;
    
    // Métricas de administración
    private volatile AtomicLong totalOperacionesAdmin = new AtomicLong(0);
    private volatile AtomicLong totalConfiguraciones = new AtomicLong(0);
    private volatile AtomicLong totalValidaciones = new AtomicLong(0);
    
    /**
     * Constructor de la InterfazAdministrativa.
     */
    public InterfazAdministrativaImpl(GestorCandidatosImpl gestorCandidatos, 
                                    AdministradorMesaImpl administradorMesa, 
                                    String dataDir) {
        this.gestorCandidatos = gestorCandidatos;
        this.administradorMesa = administradorMesa;
        this.archivoAuditoriaAdmin = dataDir + "/AuditoriaAdministrativa.log";
        
        logger.info("InterfazAdministrativa inicializada");
        logger.info("GestorCandidatos: {}", gestorCandidatos != null ? "OK" : "ERROR");
        logger.info("AdministradorMesa: {}", administradorMesa != null ? "OK" : "ERROR");
        logger.info("Archivo auditoría: {}", archivoAuditoriaAdmin);
        
        // Inicializar archivo de auditoría
        inicializarArchivoAuditoria();
    }
    
    // === DELEGACIÓN A ADMINISTRADOR MESA ===
    
    /**
     * Configura las mesas electorales del sistema.
     * 
     * @param current Contexto de Ice
     * @throws ErrorPersistenciaException Si hay error de persistencia
     */
    @Override
    public void configurarMesas(Current current) throws ErrorPersistenciaException {
        
        logger.info("=== CONFIGURAR MESAS (ADMIN) ===");
        
        try {
            totalOperacionesAdmin.incrementAndGet();
            totalConfiguraciones.incrementAndGet();
            
            // Delegar al AdministradorMesa
            administradorMesa.configurarMesas(current);
            
            // Escribir auditoría administrativa
            escribirAuditoriaAdmin("CONFIGURAR_MESAS", "ADMIN", 
                "Configuración de mesas desde interfaz administrativa");
            
            logger.info("Configuración de mesas completada desde interfaz administrativa");
            
        } catch (Exception e) {
            logger.error("Error configurando mesas desde interfaz administrativa: {}", e.getMessage());
            escribirAuditoriaAdmin("ERROR_CONFIGURAR_MESAS", "ADMIN", "Error: " + e.getMessage());
            throw new ErrorPersistenciaException("Error en configuración administrativa: " + e.getMessage());
        }
    }
    
    /**
     * Asigna votantes a las mesas electorales.
     * 
     * @param current Contexto de Ice
     * @throws MesaNoExisteException Si la mesa no existe
     * @throws ErrorPersistenciaException Si hay error de persistencia
     */
    @Override
    public void asignarVotantesMesa(Current current) 
            throws MesaNoExisteException, ErrorPersistenciaException {
        
        logger.info("=== ASIGNAR VOTANTES MESA (ADMIN) ===");
        
        try {
            totalOperacionesAdmin.incrementAndGet();
            
            // Delegar al AdministradorMesa
            administradorMesa.asignarVotantesMesa(current);
            
            // Escribir auditoría administrativa
            escribirAuditoriaAdmin("ASIGNAR_VOTANTES", "ADMIN", 
                "Asignación de votantes desde interfaz administrativa");
            
            logger.info("Asignación de votantes completada desde interfaz administrativa");
            
        } catch (MesaNoExisteException e) {
            logger.error("Mesa no existe en asignación administrativa: {}", e.getMessage());
            escribirAuditoriaAdmin("ERROR_MESA_NO_EXISTE", "ADMIN", "Error: " + e.getMessage());
            throw e;
        } catch (Exception e) {
            logger.error("Error asignando votantes desde interfaz administrativa: {}", e.getMessage());
            escribirAuditoriaAdmin("ERROR_ASIGNAR_VOTANTES", "ADMIN", "Error: " + e.getMessage());
            throw new ErrorPersistenciaException("Error en asignación administrativa: " + e.getMessage());
        }
    }
    
    /**
     * Visualiza la distribución de votantes en las mesas.
     * 
     * @param current Contexto de Ice
     * @return String con la visualización de distribución
     * @throws ErrorPersistenciaException Si hay error de persistencia
     */
    @Override
    public String visualizarDistribucion(Current current) throws ErrorPersistenciaException {
        
        logger.info("=== VISUALIZAR DISTRIBUCION (ADMIN) ===");
        
        try {
            totalOperacionesAdmin.incrementAndGet();
            
            // Delegar al AdministradorMesa
            String distribucion = administradorMesa.visualizarDistribucion(current);
            
            // Escribir auditoría administrativa
            escribirAuditoriaAdmin("VISUALIZAR_DISTRIBUCION", "ADMIN", 
                "Consulta de distribución desde interfaz administrativa");
            
            logger.info("Visualización de distribución completada desde interfaz administrativa");
            
            return distribucion;
            
        } catch (Exception e) {
            logger.error("Error visualizando distribución desde interfaz administrativa: {}", e.getMessage());
            escribirAuditoriaAdmin("ERROR_VISUALIZAR_DISTRIBUCION", "ADMIN", "Error: " + e.getMessage());
            throw new ErrorPersistenciaException("Error en visualización administrativa: " + e.getMessage());
        }
    }
    
    // === DELEGACIÓN A GESTOR CANDIDATOS ===
    
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
        
        logger.info("=== VALIDAR CANDIDATOS UNICOS (ADMIN) ===");
        
        try {
            totalOperacionesAdmin.incrementAndGet();
            totalValidaciones.incrementAndGet();
            
            // Delegar al GestorCandidatos
            boolean resultado = gestorCandidatos.validarCandidatosUnicos(current);
            
            // Escribir auditoría administrativa
            escribirAuditoriaAdmin("VALIDAR_CANDIDATOS_UNICOS", "ADMIN", 
                String.format("Validación desde interfaz administrativa - Resultado: %s", resultado));
            
            logger.info("Validación de candidatos únicos completada desde interfaz administrativa: {}", resultado);
            
            return resultado;
            
        } catch (CandidatoDuplicadoException e) {
            logger.error("Candidatos duplicados encontrados en validación administrativa: {}", e.getMessage());
            escribirAuditoriaAdmin("ERROR_CANDIDATOS_DUPLICADOS", "ADMIN", "Error: " + e.getMessage());
            throw e;
        } catch (Exception e) {
            logger.error("Error validando candidatos desde interfaz administrativa: {}", e.getMessage());
            escribirAuditoriaAdmin("ERROR_VALIDAR_CANDIDATOS", "ADMIN", "Error: " + e.getMessage());
            throw new ErrorPersistenciaException("Error en validación administrativa: " + e.getMessage());
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
        
        logger.info("=== GESTIONAR CANDIDATOS (ADMIN) ===");
        
        try {
            totalOperacionesAdmin.incrementAndGet();
            
            // Delegar al GestorCandidatos
            gestorCandidatos.gestionarCandidatos(current);
            
            // Escribir auditoría administrativa
            escribirAuditoriaAdmin("GESTIONAR_CANDIDATOS", "ADMIN", 
                "Acceso a gestión de candidatos desde interfaz administrativa");
            
            logger.info("Gestión de candidatos completada desde interfaz administrativa");
            
        } catch (Exception e) {
            logger.error("Error gestionando candidatos desde interfaz administrativa: {}", e.getMessage());
            escribirAuditoriaAdmin("ERROR_GESTIONAR_CANDIDATOS", "ADMIN", "Error: " + e.getMessage());
            throw new ErrorPersistenciaException("Error en gestión administrativa: " + e.getMessage());
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
        
        logger.info("=== IMPORTACION CANDIDATOS EXCEL (ADMIN) ===");
        
        try {
            totalOperacionesAdmin.incrementAndGet();
            
            // Delegar al GestorCandidatos
            EstadoImportacion estado = gestorCandidatos.importacionCandidatosExcel(current);
            
            // Escribir auditoría administrativa
            escribirAuditoriaAdmin("IMPORTACION_EXCEL", "ADMIN", 
                String.format("Importación desde interfaz administrativa - Estado: %s", estado));
            
            logger.info("Importación Excel completada desde interfaz administrativa: {}", estado);
            
            return estado;
            
        } catch (ImportacionException e) {
            logger.error("Error de importación en interfaz administrativa: {}", e.getMessage());
            escribirAuditoriaAdmin("ERROR_IMPORTACION", "ADMIN", "Error: " + e.getMessage());
            throw e;
        } catch (Exception e) {
            logger.error("Error importando candidatos desde interfaz administrativa: {}", e.getMessage());
            escribirAuditoriaAdmin("ERROR_IMPORTACION_CANDIDATOS", "ADMIN", "Error: " + e.getMessage());
            throw new ErrorPersistenciaException("Error en importación administrativa: " + e.getMessage());
        }
    }
    
    // === MÉTODOS ADMINISTRATIVOS ADICIONALES ===
    
    /**
     * Obtiene un resumen completo del sistema.
     */
    public String obtenerResumenSistema() {
        try {
            StringBuilder resumen = new StringBuilder();
            resumen.append("=== RESUMEN DEL SISTEMA ELECTORAL ===\n\n");
            
            // Estadísticas de candidatos
            if (gestorCandidatos != null) {
                resumen.append("CANDIDATOS:\n");
                resumen.append("   ").append(gestorCandidatos.obtenerEstadisticas()).append("\n\n");
            }
            
            // Estadísticas de mesas
            if (administradorMesa != null) {
                resumen.append("MESAS ELECTORALES:\n");
                resumen.append("   ").append(administradorMesa.obtenerEstadisticas()).append("\n\n");
            }
            
            // Estadísticas de interfaz administrativa
            resumen.append("INTERFAZ ADMINISTRATIVA:\n");
            resumen.append("   ").append(obtenerEstadisticasAdmin()).append("\n\n");
            
            resumen.append("Generado: ").append(Instant.now().toString());
            
            return resumen.toString();
            
        } catch (Exception e) {
            logger.error("Error generando resumen del sistema: {}", e.getMessage());
            return "Error generando resumen: " + e.getMessage();
        }
    }
    
    /**
     * Ejecuta operaciones de mantenimiento del sistema.
     */
    public void ejecutarMantenimiento() {
        try {
            logger.info("Ejecutando mantenimiento del sistema...");
            
            // Validar candidatos
            try {
                boolean candidatosValidos = gestorCandidatos.validarCandidatosUnicos(null);
                logger.info("Validación de candidatos: {}", candidatosValidos ? "OK" : "ERROR");
            } catch (Exception e) {
                logger.warn("Error en validación de candidatos durante mantenimiento: {}", e.getMessage());
            }
            
            // Guardar datos
            try {
                gestorCandidatos.guardarCandidatos(null);
                logger.info("Guardado de candidatos: OK");
            } catch (Exception e) {
                logger.warn("Error guardando candidatos durante mantenimiento: {}", e.getMessage());
            }
            
            // Escribir auditoría
            escribirAuditoriaAdmin("MANTENIMIENTO", "SISTEMA", 
                "Operaciones de mantenimiento ejecutadas");
            
            logger.info("Mantenimiento del sistema completado");
            
        } catch (Exception e) {
            logger.error("Error ejecutando mantenimiento: {}", e.getMessage());
        }
    }
    
    /**
     * Obtiene estadísticas de la interfaz administrativa.
     */
    public String obtenerEstadisticasAdmin() {
        return String.format(
            "InterfazAdministrativa - Operaciones: %d, Configuraciones: %d, Validaciones: %d",
            totalOperacionesAdmin.get(), totalConfiguraciones.get(), totalValidaciones.get()
        );
    }
    
    // === MÉTODOS PRIVADOS ===
    
    /**
     * Inicializa el archivo de auditoría administrativa.
     */
    private void inicializarArchivoAuditoria() {
        try {
            Path auditoriaPath = Paths.get(archivoAuditoriaAdmin);
            
            // Crear directorio si no existe
            Files.createDirectories(auditoriaPath.getParent());
            
            // Crear archivo si no existe
            if (!Files.exists(auditoriaPath)) {
                Files.createFile(auditoriaPath);
                logger.info("Archivo de auditoría administrativa creado: {}", archivoAuditoriaAdmin);
            }
            
            // Escribir inicio de sesión
            escribirAuditoriaAdmin("INICIO_SESION", "SISTEMA", "InterfazAdministrativa inicializada");
            
        } catch (Exception e) {
            logger.error("Error inicializando archivo de auditoría administrativa: {}", e.getMessage());
        }
    }
    
    /**
     * Escribe un log de auditoría administrativa.
     */
    private void escribirAuditoriaAdmin(String operacion, String usuario, String detalles) {
        try {
            String logEntry = String.format("ADMIN|%s|%s|%s|%s%n", 
                Instant.now().toString(), operacion, usuario, detalles);
            
            Files.write(Paths.get(archivoAuditoriaAdmin), logEntry.getBytes(), 
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            
        } catch (Exception e) {
            logger.error("Error escribiendo auditoría administrativa: {}", e.getMessage());
        }
    }
    
    /**
     * Finaliza la InterfazAdministrativa.
     */
    public void shutdown() {
        logger.info("Finalizando InterfazAdministrativa...");
        
        // Escribir estadísticas finales
        escribirAuditoriaAdmin("SHUTDOWN", "SISTEMA", obtenerEstadisticasAdmin());
        
        // Ejecutar mantenimiento final
        ejecutarMantenimiento();
        
        logger.info("InterfazAdministrativa finalizada");
        logger.info("Estadísticas finales: {}", obtenerEstadisticasAdmin());
    }
} 