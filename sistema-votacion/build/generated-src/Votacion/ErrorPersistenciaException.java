//
// Copyright (c) ZeroC, Inc. All rights reserved.
//
//
// Ice version 3.7.10
//
// <auto-generated>
//
// Generated from file `Common.ice'
//
// Warning: do not edit this file.
//
// </auto-generated>
//

package Votacion;

public class ErrorPersistenciaException extends com.zeroc.Ice.UserException
{
    public ErrorPersistenciaException()
    {
        this.mensaje = "";
    }

    public ErrorPersistenciaException(Throwable cause)
    {
        super(cause);
        this.mensaje = "";
    }

    public ErrorPersistenciaException(String mensaje)
    {
        this.mensaje = mensaje;
    }

    public ErrorPersistenciaException(String mensaje, Throwable cause)
    {
        super(cause);
        this.mensaje = mensaje;
    }

    public String ice_id()
    {
        return "::Votacion::ErrorPersistenciaException";
    }

    public String mensaje;

    /** @hidden */
    @Override
    protected void _writeImpl(com.zeroc.Ice.OutputStream ostr_)
    {
        ostr_.startSlice("::Votacion::ErrorPersistenciaException", -1, true);
        ostr_.writeString(mensaje);
        ostr_.endSlice();
    }

    /** @hidden */
    @Override
    protected void _readImpl(com.zeroc.Ice.InputStream istr_)
    {
        istr_.startSlice();
        mensaje = istr_.readString();
        istr_.endSlice();
    }

    /** @hidden */
    public static final long serialVersionUID = -736615003L;
}
