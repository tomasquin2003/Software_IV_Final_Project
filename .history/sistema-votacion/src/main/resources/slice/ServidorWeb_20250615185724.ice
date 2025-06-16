#pragma once

#include "Common.ice"
#include "ServidorRegional.ice"

module Votacion {
    // Enums para ServidorWeb (definidos primero)
    enum TipoFiltro { REGION, CANDIDATO, PARTIDO, MESA };
    enum EstadoSuscripcion { ACTIVA, PAUSADA, CANCELADA, EXPIRADA };
    enum TipoNotificacion { ACTUALIZACION, CAMBIO, ALERTA, FINALIZACION };

    // Estructuras de datos específicas del ServidorWeb
    struct ResultadoVotacion {
        string candidatoId;
        string nombreCandidato;
        string partido;
        int totalVotos;
        double porcentaje;
        string region;
        string timestamp;
    };

    struct ResultadosRegion {
        string regionId;
        string nombreRegion;
        int totalVotantes;
        int votosEmitidos;
        double participacion;
        string ultimaActualizacion;
    };

    struct SuscripcionResultados {
        string suscripcionId;
        string clientId;
        string region;
        TipoFiltro filtro;
        EstadoSuscripcion estado;
        string fechaCreacion;
        int intervaloActualizacion;
    };

    struct FiltroResultados {
        TipoFiltro tipo;
        string valor;
        string region;
        bool incluirDetalle;
    };

    struct EstadisticasVotacion {
        int totalMesas;
        int mesasReportadas;
        double porcentajeEscrutinio;
        int totalVotantes;
        int participacionTotal;
        string horaUltimaActualizacion;
    };

    struct NotificacionCambio {
        string regionId;
        TipoNotificacion tipo;
        string mensaje;
        string datos;
        string timestamp;
    };

    struct DatosCacheResultados {
        string clave;
        string region;
        string datosResultados;
        string timestamp;
        int ttl;
        bool valido;
    };

    struct InfoCliente {
        string clientId;
        string direccionIP;
        string agente;
        string fechaConexion;
        int suscripcionesActivas;
        EstadoSuscripcion estado;
    };

    // Excepciones específicas del ServidorWeb
    exception SuscripcionInvalidaException {
        string suscripcionId;
        string mensaje;
    };

    exception ResultadosNoDisponiblesException {
        string region;
        string mensaje;
    };

    exception ClienteNoExisteException {
        string clientId;
        string mensaje;
    };    // Interfaces del ServidorWeb

    interface PortalConsultas {
        // Requiere
        LugarVotacion consultarLugarVotacion(string cedula)
            throws VotanteNoExisteException, ErrorPersistenciaException;
    };

    interface VisualizadorResultados {
        // Requiere
        SuscripcionResultados suscribirseResultados(string clientId, FiltroResultados filtros)
            throws ClienteNoExisteException, ErrorPersistenciaException;
        void cancelarSuscripcion(string suscripcionId)
            throws SuscripcionInvalidaException, ErrorPersistenciaException;
        ResultadosRegion obtenerResultadosRegion(string region)
            throws ResultadosNoDisponiblesException, ErrorPersistenciaException;
    };

    interface PublisherResultados {
        // Provee
        void publicarActualizacion(string resultados, string region)
            throws ErrorPersistenciaException;
        void notificarCambiosVotacion(string regionId, NotificacionCambio delta)
            throws ErrorPersistenciaException;
        
        // Requiere
        void registrarTriggerActualizacion()
            throws ErrorPersistenciaException;
        string obtenerResultadosActualizados()
            throws ErrorPersistenciaException;
        double obtenerPorcentajeVotacion()
            throws ErrorPersistenciaException;
    };

    interface SubscriberManager {
        // Provee
        SuscripcionResultados suscribirseResultados(string clientId, FiltroResultados filtros)
            throws ClienteNoExisteException, ErrorPersistenciaException;
        void cancelarSuscripcion(string suscripcionId)
            throws SuscripcionInvalidaException, ErrorPersistenciaException;
        void distribuirActualizacion(string resultados, string region)
            throws ErrorPersistenciaException;
        
        // Requiere
        void almacenarResultadosTemporal(string region, DatosCacheResultados datos)
            throws ErrorPersistenciaException;
    };

    interface ResultadosCache {
        // Provee
        void almacenarResultadosTemporal(string region, DatosCacheResultados datos)
            throws ErrorPersistenciaException;
        ResultadosRegion obtenerResultadosRegion(string region)
            throws ResultadosNoDisponiblesException, ErrorPersistenciaException;
        void limpiarCacheResultados()
            throws ErrorPersistenciaException;
        EstadisticasVotacion verificarEstadoCacheResultados()
            throws ErrorPersistenciaException;
    };

    // Interfaces adicionales para conectividad remota

    interface GestorConsultasRegionalRemoto {
        // Remota hacia ServidorRegional
        LugarVotacion consultarLugarVotacion(string cedula)
            throws VotanteNoExisteException, ErrorPersistenciaException;
    };

    interface ConnectionManagerRemoto {
        // Remota hacia DatabaseProxy
        void registrarTriggerActualizacion()
            throws ErrorPersistenciaException;
        string obtenerResultadosActualizados()
            throws ErrorPersistenciaException;
        double obtenerPorcentajeVotacion()
            throws ErrorPersistenciaException;
    };

    // Interfaces de callback para notificaciones en tiempo real
    interface ResultadosCallback {
        void recibirActualizacionResultados(ResultadosRegion resultados);
        void recibirNotificacionCambio(NotificacionCambio notificacion);
    };

    interface ClienteSubscriptor {
        void notificarActualizacion(string suscripcionId, string datosResultados);
        void notificarCambio(string suscripcionId, NotificacionCambio cambio);
        void confirmarRecepcion(string suscripcionId, string timestamp);
    };
};
