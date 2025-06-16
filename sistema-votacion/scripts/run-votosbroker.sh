#!/bin/bash

# =================================================================
# SCRIPT DE EJECUCIÓN VOTOSBROKER
# =================================================================
# Script para iniciar el VotosBroker - Microservicio intermedio robusto
# para transmisión confiable de votos
#

set -e  # Salir si hay errores

# -----------------------------------------------------------------
# CONFIGURACIÓN DEL SCRIPT
# -----------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
JAVA_MAIN_CLASS="com.registraduria.votacion.broker.VotosBrokerApp"
DEFAULT_CONFIG="src/main/resources/config/broker.config"

# Colores para output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# -----------------------------------------------------------------
# FUNCIONES AUXILIARES
# -----------------------------------------------------------------

print_header() {
    echo -e "${BLUE}"
    echo "================================================================="
    echo "             VOTOSBROKER - MICROSERVICIO ROBUSTO"
    echo "================================================================="
    echo -e "${NC}"
}

print_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

check_requirements() {
    print_info "Verificando requisitos..."
    
    # Verificar Java
    if ! command -v java &> /dev/null; then
        print_error "Java no está instalado o no está en el PATH"
        exit 1
    fi
    
    # Verificar Gradle (opcional)
    if ! command -v gradle &> /dev/null; then
        print_warning "Gradle no encontrado, usando wrapper si está disponible"
    fi
    
    # Verificar directorio del proyecto
    if [[ ! -d "$PROJECT_DIR/src" ]]; then
        print_error "Directorio del proyecto no válido: $PROJECT_DIR"
        exit 1
    fi
    
    print_info "Requisitos verificados ✓"
}

setup_directories() {
    print_info "Configurando directorios..."
    
    # Crear directorios necesarios
    mkdir -p "$PROJECT_DIR/data/broker"
    mkdir -p "$PROJECT_DIR/data/broker/backups"
    mkdir -p "$PROJECT_DIR/logs"
    
    print_info "Directorios configurados ✓"
}

compile_project() {
    print_info "Compilando proyecto..."
    
    cd "$PROJECT_DIR"
    
    if [[ -f "gradlew" ]]; then
        ./gradlew build -x test
    elif command -v gradle &> /dev/null; then
        gradle build -x test
    else
        print_error "No se pudo encontrar Gradle o gradle wrapper"
        exit 1
    fi
    
    if [[ $? -eq 0 ]]; then
        print_info "Compilación exitosa ✓"
    else
        print_error "Error en compilación"
        exit 1
    fi
}

generate_ice_classes() {
    print_info "Generando clases ICE..."
    
    cd "$PROJECT_DIR"
    
    # Generar clases desde archivos .ice
    if command -v slice2java &> /dev/null; then
        slice2java --output-dir src/main/java src/main/resources/slice/*.ice
        print_info "Clases ICE generadas ✓"
    else
        print_warning "slice2java no encontrado, saltando generación ICE"
    fi
}

show_usage() {
    echo "Uso: $0 [OPCIONES]"
    echo ""
    echo "Opciones:"
    echo "  --config FILE    Archivo de configuración (default: $DEFAULT_CONFIG)"
    echo "  --port PORT      Puerto del broker (override config)"
    echo "  --debug          Habilitar modo debug"
    echo "  --compile        Compilar antes de ejecutar"
    echo "  --help           Mostrar esta ayuda"
    echo ""
    echo "Ejemplos:"
    echo "  $0                                    # Ejecución normal"
    echo "  $0 --config config/prod.config       # Con config específica"
    echo "  $0 --port 10005                      # En puerto específico"
    echo "  $0 --debug --compile                 # Debug con compilación"
}

# -----------------------------------------------------------------
# PROCESAMIENTO DE ARGUMENTOS
# -----------------------------------------------------------------

CONFIG_FILE="$DEFAULT_CONFIG"
DEBUG_MODE=false
COMPILE_FIRST=false
CUSTOM_PORT=""

while [[ $# -gt 0 ]]; do
    case $1 in
        --config)
            CONFIG_FILE="$2"
            shift 2
            ;;
        --port)
            CUSTOM_PORT="$2"
            shift 2
            ;;
        --debug)
            DEBUG_MODE=true
            shift
            ;;
        --compile)
            COMPILE_FIRST=true
            shift
            ;;
        --help)
            show_usage
            exit 0
            ;;
        *)
            print_error "Opción desconocida: $1"
            show_usage
            exit 1
            ;;
    esac
done

# -----------------------------------------------------------------
# EJECUCIÓN PRINCIPAL
# -----------------------------------------------------------------

main() {
    print_header
    
    print_info "Iniciando VotosBroker..."
    print_info "Directorio del proyecto: $PROJECT_DIR"
    print_info "Archivo de configuración: $CONFIG_FILE"
    
    # Verificaciones iniciales
    check_requirements
    setup_directories
    
    # Compilar si se solicita
    if [[ "$COMPILE_FIRST" == "true" ]]; then
        generate_ice_classes
        compile_project
    fi
    
    # Verificar archivo de configuración
    if [[ ! -f "$PROJECT_DIR/$CONFIG_FILE" ]]; then
        print_warning "Archivo de configuración no encontrado: $CONFIG_FILE"
        print_info "Usando configuración por defecto"
    fi
    
    # Configurar classpath
    CLASSPATH="$PROJECT_DIR/build/classes/java/main"
    CLASSPATH="$CLASSPATH:$PROJECT_DIR/build/resources/main"
    
    # Agregar librerías de dependencias
    if [[ -d "$PROJECT_DIR/build/libs" ]]; then
        CLASSPATH="$CLASSPATH:$PROJECT_DIR/build/libs/*"
    fi
    
    # Configurar opciones de JVM
    JVM_OPTS="-Xmx512m -Xms256m"
    
    if [[ "$DEBUG_MODE" == "true" ]]; then
        JVM_OPTS="$JVM_OPTS -Ddebug=true -Djava.util.logging.level=FINE"
        print_info "Modo debug habilitado"
    fi
    
    # Configurar argumentos del programa
    PROGRAM_ARGS="--config $CONFIG_FILE"
    
    if [[ -n "$CUSTOM_PORT" ]]; then
        PROGRAM_ARGS="$PROGRAM_ARGS --port $CUSTOM_PORT"
        print_info "Puerto personalizado: $CUSTOM_PORT"
    fi
    
    # Mostrar información de inicio
    print_info "Configuración JVM: $JVM_OPTS"
    print_info "Argumentos: $PROGRAM_ARGS"
    print_info ""
    print_info "=== INICIANDO VOTOSBROKER ==="
    
    # Cambiar al directorio del proyecto
    cd "$PROJECT_DIR"
    
    # Ejecutar el VotosBroker
    java $JVM_OPTS -cp "$CLASSPATH" $JAVA_MAIN_CLASS $PROGRAM_ARGS
}

# -----------------------------------------------------------------
# MANEJADOR DE SEÑALES
# -----------------------------------------------------------------

cleanup() {
    print_info ""
    print_info "Recibida señal de interrupción, finalizando VotosBroker..."
    exit 0
}

trap cleanup SIGINT SIGTERM

# -----------------------------------------------------------------
# PUNTO DE ENTRADA
# -----------------------------------------------------------------

main "$@" 