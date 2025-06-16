#pragma once

#include "Common.ice"

module Votacion {
    // Enums para ServidorRegional (definidos primero)
    enum EstadoCache { ACTIVO, INACTIVO, SINCRONIZANDO, ERROR };
    enum TipoConsultaRegional { LUGAR_VOTACION, MESA_ASIGNADA, HORARIOS, DIRECCION };

    // Estructuras de datos específicas del ServidorRegional
    struct LugarVotacion {
        string mesaId;
        string direccion;
        string horarioApertura;
        string horarioCierre;
        string responsable;
        string telefono;
        bool activa;
    };

    struct DatosCache {
        string clave;
        string valor;
        string timestamp;
        int ttl;
        string region;
        bool valido;
    };

    struct AsignacionMesa {
        string cedula;
        string mesaId;
        string region;
        string timestamp;
        bool confirmada;
    };

    struct InfoRegional {
        string regionId;
        string nombre;
        int totalMesas;
        int votantesAsignados;
        bool disponible;
    };    struct EstadoCacheInfo {
        int totalEntradas;
        int entradasValidas;
        int consultasExitosas;
        int fallosCache;
        string ultimaActualizacion;
        EstadoCache estado;
    };

    // Excepciones específicas del ServidorRegional
    exception CacheException {
        string operacion;
        string mensaje;
    };

    exception RegionNoDisponibleException {
        string regionId;
        string mensaje;
    };

    exception VotanteNoExisteException {
        string cedula;
        string mensaje;
    };

    // Interfaces del ServidorRegional

    interface GestorConsultasRegional {
        // Provee
        LugarVotacion consultarLugarVotacion(string cedula)
            throws VotanteNoExisteException, RegionNoDisponibleException, ErrorPersistenciaException;
        
        // Requiere
        string consultarAsignacionMesas(string cedula)
            throws VotanteNoExisteException, ErrorPersistenciaException;
        DatosCache obtenerDatosCache(string cedula)
            throws CacheException;
        void actualizarCache(DatosCache datos)
            throws CacheException;
    };

    interface CacheRegional {
        // Provee
        DatosCache obtenerDatosCache(string cedula)
            throws CacheException;
        void actualizarCache(DatosCache datos)
            throws CacheException;
        void limpiarCache()
            throws CacheException, ErrorPersistenciaException;        EstadoCacheInfo verificarEstadoCache()
            throws CacheException;
    };

    // Interfaces adicionales para completar las conexiones remotas

    interface ControllerVotacionLocal {
        // Requiere (remota)
        LugarVotacion consultarLugarVotacion(string cedula)
            throws VotanteNoExisteException, RegionNoDisponibleException, ErrorPersistenciaException;
    };

    interface PortalConsultas {
        // Requiere (remota)
        LugarVotacion consultarLugarVotacion(string cedula)
            throws VotanteNoExisteException, RegionNoDisponibleException, ErrorPersistenciaException;
    };

    // Interface para conexión con AdministradorMesa (ya definida en ServidorCentral)
    // Se incluye aquí para referencias remotas
    interface AdministradorMesaRemoto {
        string consultarAsignacionMesas(string cedula)
            throws VotanteNoExisteException, ErrorPersistenciaException;
    };
};
