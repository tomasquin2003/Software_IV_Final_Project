package com.registraduria.votacion.estacion.util;

import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Singleton para compartir información entre componentes de la estación.
 * Permite asociar votoId con cédulas de votantes.
 */
public class EstacionContext {
    private static final Logger logger = LoggerFactory.getLogger(EstacionContext.class);
    
    private static EstacionContext instance;
    
    // Mapa para relacionar votoId con cédula del votante
    private final ConcurrentHashMap<String, String> votoIdToCedula = new ConcurrentHashMap<>();
    
    private EstacionContext() {
        logger.info("EstacionContext inicializado");
    }
    
    /**
     * Obtiene la instancia singleton.
     * 
     * @return Instancia única de EstacionContext
     */
    public static synchronized EstacionContext getInstance() {
        if (instance == null) {
            instance = new EstacionContext();
        }
        return instance;
    }
    
    /**
     * Registra la relación entre un votoId y la cédula del votante.
     * 
     * @param votoId ID del voto
     * @param cedula Cédula del votante
     */
    public void registrarVotoCedula(String votoId, String cedula) {
        votoIdToCedula.put(votoId, cedula);
        logger.debug("Registrada relación votoId {} -> cédula {}", votoId, cedula);
    }
    
    /**
     * Obtiene la cédula asociada a un votoId.
     * 
     * @param votoId ID del voto
     * @return Cédula del votante o "DESCONOCIDA" si no se encuentra
     */
    public String obtenerCedulaPorVotoId(String votoId) {
        String cedula = votoIdToCedula.get(votoId);
        if (cedula == null) {
            logger.warn("No se encontró cédula para votoId: {}", votoId);
            return "DESCONOCIDA";
        }
        return cedula;
    }
    
    /**
     * Limpia la relación de un voto (para liberar memoria).
     * 
     * @param votoId ID del voto a limpiar
     */
    public void limpiarVoto(String votoId) {
        String cedula = votoIdToCedula.remove(votoId);
        if (cedula != null) {
            logger.debug("Limpiada relación votoId {} -> cédula {}", votoId, cedula);
        }
    }
}
