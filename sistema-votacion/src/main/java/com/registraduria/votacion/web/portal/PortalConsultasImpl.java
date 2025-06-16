package com.registraduria.votacion.web.portal;

import Votacion.*;
import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.Current;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * PortalConsultasImpl - Implementación del portal de consultas públicas.
 * 
 * Características:
 * - Consultas de lugar de votación para ciudadanos
 * - Conectividad con ServidorRegional
 * - Manejo robusto de errores
 * - Métricas de consultas
 * - Thread-safe
 */
public class PortalConsultasImpl implements PortalConsultas {
    private static final Logger logger = LoggerFactory.getLogger(PortalConsultasImpl.class);
    
    // Comunicación ICE
    private final Communicator communicator;
    private final String endpointRegional;
    private GestorConsultasRegionalPrx gestorRemoto;
    
    // Métricas
    private long consultasExitosas = 0;
    private long consultasFallidas = 0;
    private long intentosReconexion = 0;
    
    // Control de conectividad
    private boolean conectadoRegional = false;
    private long ultimaConexion = 0;
    private static final int TIMEOUT_CONEXION = 5000; // 5 segundos
    private static final int MAX_REINTENTOS = 3;
    
    /**
     * Constructor del PortalConsultas.
     */
    public PortalConsultasImpl(Communicator communicator, String endpointRegional) {
        this.communicator = communicator;
        this.endpointRegional = endpointRegional;
        
        establecerConexionRegional();
        
        logger.info("PortalConsultas inicializado - Endpoint: {}", endpointRegional);
    }
    
    @Override
    public LugarVotacion consultarLugarVotacion(String cedula, Current current) 
            throws VotanteNoExisteException, ErrorPersistenciaException {
        
        if (cedula == null || cedula.trim().isEmpty()) {
            consultasFallidas++;
            throw new VotanteNoExisteException(cedula, "Cédula no puede estar vacía");
        }
        
        cedula = cedula.trim().toUpperCase();
        
        try {
            logger.debug("Consultando lugar de votación para cédula: {}", cedula);
            
            // Verificar conectividad
            if (!conectadoRegional || gestorRemoto == null) {
                if (!establecerConexionRegional()) {
                    consultasFallidas++;
                    throw new ErrorPersistenciaException("Sin conectividad con ServidorRegional");
                }
            }
            
            // Realizar consulta con reintentos
            LugarVotacion resultado = null;
            int intentos = 0;
            
            while (intentos < MAX_REINTENTOS) {
                try {
                    resultado = gestorRemoto.consultarLugarVotacion(cedula, null);
                    consultasExitosas++;
                    logger.debug("Consulta exitosa para cédula: {}", cedula);
                    break;
                    
                } catch (Exception e) {
                    intentos++;
                    logger.warn("Intento {} fallido para cédula {}: {}", intentos, cedula, e.getMessage());
                    
                    if (intentos < MAX_REINTENTOS) {
                        // Intentar reconectar
                        if (!establecerConexionRegional()) {
                            Thread.sleep(1000); // Esperar 1 segundo antes del siguiente intento
                        }
                    } else {
                        // Último intento fallido
                        consultasFallidas++;
                        if (e instanceof VotanteNoExisteException) {
                            throw e;
                        }
                        throw new ErrorPersistenciaException("Error consultando despues de " + MAX_REINTENTOS + " intentos: " + e.getMessage());
                    }
                }
            }
            
            return resultado;
            
        } catch (VotanteNoExisteException | ErrorPersistenciaException e) {
            throw e;
        } catch (Exception e) {
            consultasFallidas++;
            logger.error("Error inesperado consultando lugar de votación: {}", e.getMessage());
            throw new ErrorPersistenciaException("Error inesperado: " + e.getMessage());
        }
    }
    
    /**
     * Establece conexión con el ServidorRegional.
     */
    private boolean establecerConexionRegional() {
        try {
            logger.debug("Estableciendo conexión con ServidorRegional...");
            
            if (communicator == null) {
                logger.error("Communicator no disponible");
                return false;
            }
            
            // Crear proxy
            var proxy = communicator.stringToProxy(endpointRegional);
            if (proxy == null) {
                logger.error("No se pudo crear proxy para: {}", endpointRegional);
                return false;
            }
            
            // Configurar timeout
            proxy = proxy.ice_timeout(TIMEOUT_CONEXION);
            
            // Verificar conexión
            gestorRemoto = GestorConsultasRegionalPrx.checkedCast(proxy);
            if (gestorRemoto == null) {
                logger.error("No se pudo conectar a GestorConsultasRegional en: {}", endpointRegional);
                return false;
            }
            
            // Probar conexión con ping
            gestorRemoto.ice_ping();
            
            conectadoRegional = true;
            ultimaConexion = System.currentTimeMillis();
            intentosReconexion++;
            
            logger.info("Conexión establecida exitosamente con ServidorRegional");
            return true;
            
        } catch (Exception e) {
            logger.error("Error estableciendo conexión con ServidorRegional: {}", e.getMessage());
            conectadoRegional = false;
            gestorRemoto = null;
            return false;
        }
    }
    
    /**
     * Verifica el estado de la conexión.
     */
    public boolean probarConectividad() {
        try {
            if (!conectadoRegional || gestorRemoto == null) {
                return establecerConexionRegional();
            }
            
            // Verificar si la conexión sigue activa
            gestorRemoto.ice_ping();
            return true;
            
        } catch (Exception e) {
            logger.warn("Conectividad perdida con ServidorRegional: {}", e.getMessage());
            conectadoRegional = false;
            return establecerConexionRegional();
        }
    }
    
    /**
     * Obtiene estadísticas de consultas.
     */
    public String obtenerEstadisticasConsultas() {
        long totalConsultas = consultasExitosas + consultasFallidas;
        double tasaExito = totalConsultas > 0 ? 
            (double) consultasExitosas / totalConsultas * 100 : 0;
        
        String estado = conectadoRegional ? "CONECTADO" : "DESCONECTADO";
        String ultimaConexionStr = ultimaConexion > 0 ? 
            LocalDateTime.ofEpochSecond(ultimaConexion / 1000, 0, java.time.ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : "Nunca";
        
        return String.format(
            "PortalConsultas - Estado: %s | Consultas exitosas: %d | Fallidas: %d | " +
            "Tasa éxito: %.2f%% | Reconexiones: %d | Última conexión: %s",
            estado, consultasExitosas, consultasFallidas, tasaExito, 
            intentosReconexion, ultimaConexionStr
        );
    }
    
    /**
     * Reinicia estadísticas.
     */
    public void reiniciarEstadisticas() {
        consultasExitosas = 0;
        consultasFallidas = 0;
        intentosReconexion = 0;
        logger.info("Estadísticas del PortalConsultas reiniciadas");
    }
    
    /**
     * Obtiene información detallada del estado.
     */
    public String obtenerEstadoDetallado() {
        StringBuilder estado = new StringBuilder();
        estado.append("=== PORTAL CONSULTAS ===\n");
        estado.append("Estado conexión: ").append(conectadoRegional ? "ACTIVA" : "INACTIVA").append("\n");
        estado.append("Endpoint regional: ").append(endpointRegional).append("\n");
        estado.append("Consultas exitosas: ").append(consultasExitosas).append("\n");
        estado.append("Consultas fallidas: ").append(consultasFallidas).append("\n");
        estado.append("Intentos reconexión: ").append(intentosReconexion).append("\n");
        estado.append("Timeout configurado: ").append(TIMEOUT_CONEXION).append("ms\n");
        estado.append("Max reintentos: ").append(MAX_REINTENTOS).append("\n");
        
        if (ultimaConexion > 0) {
            long tiempoConexion = System.currentTimeMillis() - ultimaConexion;
            estado.append("Tiempo desde última conexión: ").append(tiempoConexion / 1000).append("s\n");
        }
        
        return estado.toString();
    }
    
    /**
     * Fuerza reconexión con el ServidorRegional.
     */
    public boolean forzarReconexion() {
        logger.info("Forzando reconexión con ServidorRegional...");
        conectadoRegional = false;
        gestorRemoto = null;
        return establecerConexionRegional();
    }
    
    /**
     * Valida el formato de cédula.
     */
    private boolean validarFormatoCedula(String cedula) {
        if (cedula == null || cedula.trim().isEmpty()) {
            return false;
        }
        
        cedula = cedula.trim();
        
        // Validaciones básicas
        if (cedula.length() < 8 || cedula.length() > 12) {
            return false;
        }
        
        // Solo números y posibles guiones
        return cedula.matches("^[0-9-]+$");
    }
    
    /**
     * Obtiene métricas en formato JSON simple.
     */
    public String obtenerMetricasJSON() {
        long total = consultasExitosas + consultasFallidas;
        return String.format(
            "{\"consultas_exitosas\":%d,\"consultas_fallidas\":%d,\"total\":%d," +
            "\"conectado\":%s,\"reconexiones\":%d,\"ultima_conexion\":%d}",
            consultasExitosas, consultasFallidas, total, 
            conectadoRegional, intentosReconexion, ultimaConexion
        );
    }
    
    /**
     * Ejecuta verificación periódica de salud.
     */
    public void verificarSalud() {
        try {
            if (!probarConectividad()) {
                logger.warn("Verificación de salud fallida - Sin conectividad con ServidorRegional");
            } else {
                logger.debug("Verificación de salud exitosa");
            }
        } catch (Exception e) {
            logger.error("Error en verificación de salud: {}", e.getMessage());
        }
    }
    
    /**
     * Cierre ordenado del portal.
     */
    public void cerrar() {
        logger.info("Cerrando PortalConsultas...");
        
        try {
            conectadoRegional = false;
            gestorRemoto = null;
            
            logger.info("OK PortalConsultas cerrado exitosamente");
            logger.info("Estadísticas finales: {}", obtenerEstadisticasConsultas());
            
        } catch (Exception e) {
            logger.error("Error cerrando PortalConsultas: {}", e.getMessage());
        }
    }
} 