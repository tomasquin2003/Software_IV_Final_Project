package com.registraduria.votacion.estacion.monitoreo;

import Votacion.SistemaMonitorizacion;
import Votacion.VerificadorAsignacionPrx;
import Votacion.VotanteNoAutorizadoException;
import com.zeroc.Ice.Current;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementación del servicio SistemaMonitorizacion que se encarga de iniciar
 * el proceso de verificación de un votante.
 */
public class SistemaMonitorizacionImpl implements SistemaMonitorizacion {
    private static final Logger logger = LoggerFactory.getLogger(SistemaMonitorizacionImpl.class);
    
    // Proxy para acceder al servicio VerificadorAsignacion
    private final VerificadorAsignacionPrx verificadorAsignacion;
    
    /**
     * Constructor que recibe el proxy para el VerificadorAsignacion.
     * 
     * @param verificadorAsignacion Proxy para el servicio VerificadorAsignacion
     */
    public SistemaMonitorizacionImpl(VerificadorAsignacionPrx verificadorAsignacion) {
        this.verificadorAsignacion = verificadorAsignacion;
        logger.info("SistemaMonitorizacion inicializado con VerificadorAsignacion");
    }

    /**
     * Inicia el proceso de verificación de un votante.
     * 
     * @param cedula Cédula del votante a verificar
     * @param current Contexto de la llamada Ice
     * @return true si el votante está autorizado para votar
     * @throws VotanteNoAutorizadoException si el votante no está autorizado
     */
    @Override
    public boolean iniciarVerificacion(String cedula, Current current) throws VotanteNoAutorizadoException {
        logger.info("Iniciando verificación para cédula: {}", cedula);
        
        try {
            // Validar si el votante está asignado a esta mesa
            boolean resultado = verificadorAsignacion.validarMesa(cedula);
            
            if (resultado) {
                logger.info("Verificación exitosa para cédula: {}", cedula);
                // Aquí se podrían realizar verificaciones adicionales si fuera necesario
                return true;
            } else {
                // Este caso no debería ocurrir según la lógica actual, 
                // pero lo mantenemos por si la implementación de validarMesa cambia
                logger.warn("Votante no autorizado para esta mesa: {}", cedula);
                throw new VotanteNoAutorizadoException(cedula, "Votante no autorizado para esta mesa");
            }
        } catch (VotanteNoAutorizadoException e) {
            logger.warn("Excepción durante la verificación: {}", e.mensaje);
            throw e; // Re-lanzar la excepción para que la maneje el controller
        } catch (Exception e) {
            logger.error("Error durante la verificación del votante", e);
            throw new VotanteNoAutorizadoException(cedula, "Error en el sistema de verificación: " + e.getMessage());
        }
    }
}