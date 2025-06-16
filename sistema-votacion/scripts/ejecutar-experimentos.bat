@echo off
echo ====================================
echo   EXPERIMENTOS SISTEMA ELECTORAL    
echo ====================================
echo.

REM Instalar dependencias Python
echo [1/4] Instalando dependencias Python...
pip install -r requirements.txt
if %errorlevel% neq 0 (
    echo ERROR: No se pudieron instalar las dependencias
    pause
    exit /b 1
)
echo.

REM Ejecutar experimentos de rendimiento
echo [2/4] Ejecutando experimentos de rendimiento...
echo NOTA: Este proceso puede tomar 30-60 minutos
python scripts/experimento-rendimiento.py
if %errorlevel% neq 0 (
    echo ERROR: Los experimentos fallaron
    pause
    exit /b 1
)
echo.

REM Generar informe en Word
echo [3/4] Generando informe en Word...
python scripts/generar-informe-word.py
if %errorlevel% neq 0 (
    echo ERROR: No se pudo generar el informe
    pause
    exit /b 1
)
echo.

REM Mostrar resultados
echo [4/4] Mostrando archivos generados...
echo.
echo ✅ EXPERIMENTOS COMPLETADOS EXITOSAMENTE
echo.
echo 📁 Archivos generados:
dir /b *.json *.csv *.txt *.docx 2>nul
echo.
echo 📋 Para revisar los resultados:
echo    • Abra el archivo .docx para el informe completo
echo    • Use el archivo .csv para análisis adicional
echo    • Revise el archivo .json para datos detallados
echo.

pause 