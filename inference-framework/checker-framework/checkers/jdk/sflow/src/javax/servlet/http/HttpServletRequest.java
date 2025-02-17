package javax.servlet.http;

import checkers.inference.sflow.quals.Tainted;
import checkers.inference.sflow.quals.Poly;
import checkers.inference.reim.quals.*;
import java.io.*;
import java.sql.*;
import java.util.*;
import javax.servlet.*;

public interface HttpServletRequest extends ServletRequest {
    /*@Tainted*/ /*@Poly*/ Cookie /*@Tainted*/ [] getCookies(/*>>> @Readonly HttpServletRequest this*/ );
    long getDateHeader(/*>>> @Readonly HttpServletRequest this,*/ String arg0);
    /*@Tainted*/ String getHeader(/*>>> @Readonly HttpServletRequest this,*/ String arg0);
    /*@Tainted*/ Enumeration<String> getHeaders(/*>>> @Readonly HttpServletRequest this,*/ String arg0);
    Enumeration<String> getHeaderNames(/*>>> @Readonly HttpServletRequest this*/ );
    int getIntHeader(/*>>> @Readonly HttpServletRequest this,*/ String arg0);
    String getMethod(/*>>> @Readonly HttpServletRequest this*/ );
    String getPathInfo(/*>>> @Readonly HttpServletRequest this*/ );
    String getPathTranslated(/*>>> @Readonly HttpServletRequest this*/ );
    String getContextPath(/*>>> @Readonly HttpServletRequest this*/ );
    /*@Tainted*/ String getQueryString(/*>>> @Readonly HttpServletRequest this*/ );
    /*@Tainted*/ String getRemoteUser(/*>>> @Readonly HttpServletRequest this*/ );
    boolean isUserInRole(/*>>> @Readonly HttpServletRequest this,*/ String arg0);
    java.security.Principal getUserPrincipal(/*>>> @Readonly HttpServletRequest this*/ );
    /*@Tainted*/ String getRequestedSessionId(/*>>> @Readonly HttpServletRequest this*/ );
    String getRequestURI(/*>>> @Readonly HttpServletRequest this*/ );
    StringBuffer getRequestURL(/*>>> @Readonly HttpServletRequest this*/ );
    String getServletPath(/*>>> @Readonly HttpServletRequest this*/ );
    HttpSession getSession(/*>>> @Readonly HttpServletRequest this,*/ boolean arg0);
    HttpSession getSession(/*>>> @Readonly HttpServletRequest this*/ );
    boolean isRequestedSessionIdValid(/*>>> @Readonly HttpServletRequest this*/ );
    boolean isRequestedSessionIdFromCookie(/*>>> @Readonly HttpServletRequest this*/ );
    boolean isRequestedSessionIdFromURL(/*>>> @Readonly HttpServletRequest this*/ );
    boolean isRequestedSessionIdFromUrl(/*>>> @Readonly HttpServletRequest this*/ );
    boolean authenticate(HttpServletResponse arg0) throws IOException,ServletException;
    void login(String arg0, String arg1) throws ServletException;
    void logout() throws ServletException;
    Collection<Part> getParts(/*>>> @Readonly HttpServletRequest this*/ ) throws IOException,ServletException;
    Part getPart(/*>>> @Readonly HttpServletRequest this,*/ String arg0) throws IOException,ServletException;
}
