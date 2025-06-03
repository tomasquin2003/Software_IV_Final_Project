package com.registraduria.votacion.estacion.controller;

import Votacion.ControllerEstacion;
import Votacion.SistemaMonitorizacionPrx;
import Votacion.VotanteNoAutorizadoException;
import com.zeroc.Ice.Current;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * Implementación del servicio ControllerEstacion que proporciona el punto de entrada
 * para la autenticación de votantes.
 */
public class ControllerEstacionImpl implements ControllerEstacion {
    private static final Logger logger = LoggerFactory.getLogger(ControllerEstacionImpl.class);
    
    // Proxy para acceder al servicio SistemaMonitorizacion
    private final SistemaMonitorizacionPrx sistemaMonitorizacion;
    
    // Conjunto para almacenar las cédulas que ya han votado en esta sesión
    // En un sistema real, esto se verificaría contra una base de datos persistente
    private final Set<String> cedulasVotadas = new HashSet<>();
    
    /**
     * Constructor que recibe el proxy para el SistemaMonitorizacion.
     * 
     * @param sistemaMonitorizacion Proxy para el servicio SistemaMonitorizacion
     */
    public ControllerEstacionImpl(SistemaMonitorizacionPrx sistemaMonitorizacion) {
        this.sistemaMonitorizacion = sistemaMonitorizacion;
        logger.info("ControllerEstacion inicializado con SistemaMonitorizacion");
    }

    /**
     * Autentica a un votante para permitirle emitir su voto.
     * 
     * @param cedula Cédula del votante a autenticar
     * @param current Contexto de la llamada Ice
     * @return true si el votante está autorizado para votar
     * @throws VotanteNoAutorizadoException si el votante no está autorizado o ya ha votado
     */
    @Override
    public boolean autenticarVotante(String cedula, Current current) throws VotanteNoAutorizadoException {
        logger.info("Solicitando autenticación para cédula: {}", cedula);
        
        // Verificar si el votante ya ha votado en esta sesión
        synchronized (cedulasVotadas) {
            if (cedulasVotadas.contains(cedula)) {
                logger.warn("Votante con cédula {} ya ha votado", cedula);
                throw new VotanteNoAutorizadoException(cedula, "Este votante ya ha emitido su voto");
            }
        }
        
        try {
            // Iniciar el proceso de verificación a través del SistemaMonitorizacion
            boolean resultado = sistemaMonitorizacion.iniciarVerificacion(cedula);
            
            if (resultado) {
                logger.info("Votante con cédula {} autenticado exitosamente", cedula);
                // En un sistema real, no agregaríamos la cédula a la lista de votantes 
                // hasta que el voto sea confirmado, pero lo hacemos aquí por simplicidad
                synchronized (cedulasVotadas) {
                    cedulasVotadas.add(cedula);
                }
                return true;
            } else {
                // Este caso no debería ocurrir según la implementación actual
                logger.warn("Autenticación fallida para cédula: {}", cedula);
                return false;
            }
        } catch (VotanteNoAutorizadoException e) {
            logger.warn("Excepción durante la autenticación: {}", e.mensaje);
            throw e; // Re-lanzar la excepción para que la maneje la UI
        } catch (Exception e) {
            logger.error("Error durante la autenticación del votante", e);
            throw new VotanteNoAutorizadoException(cedula, "Error en el sistema de autenticación: " + e.getMessage());
        }
    }
}