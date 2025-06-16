#!/bin/bash

# Script para ejecutar el Servidor Regional
# Sistema Electoral Distribuido

echo "========================================"
echo "    INICIANDO SERVIDOR REGIONAL"
echo "========================================"

# Configuración
REGION_ID=${1:-"REGION_01"}
PUERTO=${2:-"10005"}
DATA_DIR="./data/regional"

echo "Región: $REGION_ID"
echo "Puerto: $PUERTO"
echo "Directorio datos: $DATA_DIR"
echo ""

# Crear directorios necesarios
echo "Creando directorios..."
mkdir -p "$DATA_DIR/cache"
mkdir -p "./logs"

# Variables de entorno
export REGION_ID="$REGION_ID"
export SERVER_PORT="$PUERTO"

# Ejecutar con Gradle
echo "Ejecutando ServidorRegional..."
echo "Presiona Ctrl+C para detener"
echo ""

cd "$(dirname "$0")/.." || exit 1
./gradlew runRegional

echo ""
echo "ServidorRegional finalizado." 