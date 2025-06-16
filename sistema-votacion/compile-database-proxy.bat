@echo off
echo ====================================
echo    COMPILANDO DATABASE PROXY
echo ====================================

echo.
echo Compilando componentes del DatabaseProxy...
echo.

gradle build --no-daemon -x test

if %ERRORLEVEL% EQU 0 (
    echo.
    echo ====================================
    echo    COMPILACION EXITOSA
    echo ====================================
    echo.
    echo DatabaseProxy compilado correctamente!
    echo.
    echo Componentes implementados:
    echo • ConnectionManager: Gateway principal
    echo • QueryRouter: Enrutamiento inteligente  
    echo • FailoverHandler: Conmutacion automatica
    echo • CircuitBreakerService: Proteccion de fallos
    echo • CacheService: Cache con TTL
    echo • RDBMSPrimary/Replica: Adaptadores BD
    echo • DatabaseProxyApp: Aplicacion principal
    echo.
    echo Listo para ejecutar con:
    echo   gradle run -PmainClass=com.registraduria.votacion.database.DatabaseProxyApp
    echo.
) else (
    echo.
    echo ====================================
    echo    ERROR EN COMPILACION
    echo ====================================
    echo.
    echo Revise los errores mostrados arriba
    echo.
)

pause 