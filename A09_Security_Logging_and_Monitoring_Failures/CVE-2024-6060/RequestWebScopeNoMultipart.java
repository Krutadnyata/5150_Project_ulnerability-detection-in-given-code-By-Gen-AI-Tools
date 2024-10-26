/**
 * Copyright (C) 2006-2014 phloc systems
 * http://www.phloc.com
 * office[at]phloc[dot]com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.phloc.webscopes.impl;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.phloc.commons.ValueEnforcer;
import com.phloc.commons.annotations.Nonempty;
import com.phloc.commons.annotations.OverrideOnDemand;
import com.phloc.commons.annotations.ReturnsMutableCopy;
import com.phloc.commons.charset.CCharset;
import com.phloc.commons.collections.ArrayHelper;
import com.phloc.commons.collections.ContainerHelper;
import com.phloc.commons.equals.EqualsUtils;
import com.phloc.commons.idfactory.GlobalIDFactory;
import com.phloc.commons.io.streams.StreamUtils;
import com.phloc.commons.lang.CGStringHelper;
import com.phloc.commons.mime.CMimeType;
import com.phloc.commons.mime.MimeType;
import com.phloc.commons.mime.MimeTypeParser;
import com.phloc.commons.state.EChange;
import com.phloc.commons.string.StringHelper;
import com.phloc.commons.string.ToStringGenerator;
import com.phloc.commons.url.ISimpleURL;
import com.phloc.commons.url.SimpleURL;
import com.phloc.json.IJSONObject;
import com.phloc.json.impl.JSONParsingException;
import com.phloc.json.impl.JSONReader;
import com.phloc.scopes.AbstractMapBasedScope;
import com.phloc.scopes.ScopeUtils;
import com.phloc.web.fileupload.IFileItem;
import com.phloc.web.http.EHTTPMethod;
import com.phloc.web.http.EHTTPVersion;
import com.phloc.web.servlet.request.IRequestParamMap;
import com.phloc.web.servlet.request.RequestHelper;
import com.phloc.web.servlet.request.RequestParamMap;
import com.phloc.webscopes.domain.IRequestWebScope;

/**
 * A request web scopes that does not parse multi-part requests.
 * 
 * @author Boris Gregorcic
 */
public class RequestWebScopeNoMultipart extends AbstractMapBasedScope implements IRequestWebScope
{
  private static final Logger LOG = LoggerFactory.getLogger (RequestWebScopeNoMultipart.class);
  // Because of transient field
  private static final long serialVersionUID = 78563987233146L;

  private static final String REQUEST_ATTR_SCOPE_INITED = "$request.scope.inited"; //$NON-NLS-1$
  private static final String REQUEST_ATTR_REQUESTPARAMMAP = "$request.scope.requestparammap"; //$NON-NLS-1$
  public static final String REQUEST_ATTR_PARSE_JSON_BODY = "$request.scope.jsonbody"; //$NON-NLS-1$
  public static final String REQUEST_ATTR_JSON_BODY_PARSED = "$request.scope.jsonbody.parsed"; //$NON-NLS-1$

  protected final transient HttpServletRequest m_aHttpRequest;
  protected final transient HttpServletResponse m_aHttpResponse;

  @Nonnull
  @Nonempty
  private static String _createScopeID (@Nonnull final HttpServletRequest aHttpRequest)
  {
    ValueEnforcer.notNull (aHttpRequest, "HttpRequest"); //$NON-NLS-1$

    return GlobalIDFactory.getNewIntID () + "@" + aHttpRequest.getRequestURI (); //$NON-NLS-1$
  }

  public RequestWebScopeNoMultipart (@Nonnull final HttpServletRequest aHttpRequest,
                                     @Nonnull final HttpServletResponse aHttpResponse)
  {
    super (_createScopeID (aHttpRequest));

    this.m_aHttpRequest = aHttpRequest;
    this.m_aHttpResponse = ValueEnforcer.notNull (aHttpResponse, "HttpResponse"); //$NON-NLS-1$

    // done initialization
    if (ScopeUtils.debugRequestScopeLifeCycle (LOG))
    {
      LOG.info ("Created request web scope '" + //$NON-NLS-1$
                getID () +
                "' of class " + //$NON-NLS-1$
                CGStringHelper.getClassLocalName (this),
                ScopeUtils.getDebugStackTrace ());
    }
  }

  @SuppressWarnings ("static-method")
  @OverrideOnDemand
  protected boolean addSpecialRequestAttributes ()
  {
    return false;
  }

  @OverrideOnDemand
  protected void postAttributeInit ()
  {
    // empty by default
  }

  @Override
  public final void initScope ()
  {
    // Avoid double initialization of a scope, because for file uploads, the
    // parameters can only be extracted once!
    // As the parameters are stored directly in the HTTP request, we're not
    // loosing any data here!
    if (getAndSetAttributeFlag (REQUEST_ATTR_SCOPE_INITED))
    {
      LOG.warn ("Scope was already inited: " + toString ()); //$NON-NLS-1$
      return;
    }

    propagateDisaptcherErrors ();

    // where some extra items (like file items) handled?
    final boolean bAddedSpecialRequestAttrs = addSpecialRequestAttributes ();

    // set parameters as attributes (handles GET and POST parameters)
    final Enumeration <?> aEnum = this.m_aHttpRequest.getParameterNames ();
    while (aEnum.hasMoreElements ())
    {
      final String sParamName = (String) aEnum.nextElement ();

      // Check if it is a single value or not
      final String [] aParamValues = this.m_aHttpRequest.getParameterValues (sParamName);

      LOG.info (sParamName + ":" + StringHelper.getImploded (',', aParamValues));

      // Avoid double setting a parameter!
      if (bAddedSpecialRequestAttrs && containsAttribute (sParamName))
      {
        continue;
      }

      if (aParamValues.length == 1)
      {
        LOG.info (setAttribute (sParamName, aParamValues[0]).name ());
      }
      else
      {
        LOG.info (setAttribute (sParamName, aParamValues).name ());
      }
    }
    try
    {
      RequestInitializerHandler.getInstance ().initRequestScope (this);
    }
    catch (final Exception aEx)
    {
      LOG.error ("Exception caught while initializing request scope:", aEx); //$NON-NLS-1$
    }

    if (getAttributeAsBoolean (REQUEST_ATTR_PARSE_JSON_BODY) &&
        getAttributeObject (ERequestDispatcherErrors.ERROR_STATUS_CODE.getID ()) == null)
    {
      initJSONBody ();
    }

    postAttributeInit ();

    // done initialization
    if (ScopeUtils.debugRequestScopeLifeCycle (LOG))
    {
      LOG.info ("Initialized request web scope '" + //$NON-NLS-1$
                getID () +
                "' of class " + //$NON-NLS-1$
                CGStringHelper.getClassLocalName (this),
                ScopeUtils.getDebugStackTrace ());
    }
    LOG.info ("SCOPE: " + this);

  }

  @Override
  @Nonnull
  public EChange setAttribute (@Nonnull final String sName, @Nullable final Object aValue)
  {
    LOG.warn ("SET: " + sName + ":" + aValue, new IllegalStateException ("OIDA"));
    return super.setAttribute (sName, aValue);
  }

  /**
   * If the request body contains data that is valid JSON, all JSON properties
   * will be added as attributes. This mechanism can be used to transfer big
   * data.
   */
  private void initJSONBody ()
  {
    LOG.debug ("Performing JSON body initialization..."); //$NON-NLS-1$
    try
    {
      if (this.m_aHttpRequest.getContentLength () > 0)
      {
        final MimeType aMimeType = MimeTypeParser.parseMimeType (this.m_aHttpRequest.getContentType ());
        if (aMimeType != null &&
            aMimeType.getAsStringWithoutParameters ()
                     .equals (CMimeType.APPLICATION_JSON.getAsStringWithoutParameters ()))
        {
          final String sJSON = StreamUtils.getAllBytesAsString (this.m_aHttpRequest.getInputStream (),
                                                                CCharset.CHARSET_UTF_8_OBJ);
          if (StringHelper.isEmpty (sJSON))
          {
            LOG.warn ("Received empty JSON body in request (may be due to previous IO problem)."); //$NON-NLS-1$
            return;
          }
          final IJSONObject aJSON = JSONReader.parseObject (sJSON);
          for (final String sProperty : aJSON.getAllPropertyNames ())
          {
            Object aVal;
            // try as list (to really only store the internal list data values)
            // aVal = aJSON.getListProperty (sProperty);
            // if (aVal == null)
            // {
            aVal = aJSON.getPropertyValueData (sProperty);
            // }
            setAttribute (sProperty, aVal);
          }
          setAttribute (REQUEST_ATTR_JSON_BODY_PARSED, true);
        }
      }
    }
    catch (final IOException aEx)
    {
      LOG.error ("Error reading request body", aEx); //$NON-NLS-1$
    }
    catch (final JSONParsingException aEx)
    {
      LOG.error ("Error parsing JSON request body", aEx); //$NON-NLS-1$
    }
    catch (final UnsupportedOperationException aEx)
    {
      // for mock requests it is not possible to get the content length
    }
  }

  /**
   * Copy error attributes added by the Servlet Container also to the scope
   * attributes
   */
  private void propagateDisaptcherErrors ()
  {
    for (final ERequestDispatcherErrors eDispatcherError : ERequestDispatcherErrors.values ())
    {
      final Object aValue = this.m_aHttpRequest.getAttribute (eDispatcherError.getID ());
      if (aValue != null)
      {
        setAttribute (eDispatcherError.getID (), aValue);
      }
    }
  }

  @Override
  protected void postDestroy ()
  {
    if (ScopeUtils.debugRequestScopeLifeCycle (LOG))
    {
      LOG.info ("Destroyed request web scope '" + //$NON-NLS-1$
                getID () +
                "' of class " + //$NON-NLS-1$
                CGStringHelper.getClassLocalName (this),
                ScopeUtils.getDebugStackTrace ());
    }
  }

  @Override
  @Nonnull
  @Nonempty
  public final String getSessionID ()
  {
    return getSessionID (true);
  }

  @Override
  @Nullable
  public final String getSessionID (final boolean bCreateIfNotExisting)
  {
    final HttpSession aSession = this.m_aHttpRequest.getSession (bCreateIfNotExisting);
    return aSession == null ? null : aSession.getId ();
  }

  @Override
  @Nullable
  public List <String> getAttributeValues (@Nullable final String sName)
  {
    return getAttributeValues (sName, null);
  }

  @Override
  @Nullable
  public List <String> getAttributeValues (@Nullable final String sName, @Nullable final List <String> aDefault)
  {
    final Object aValue = getAttributeObject (sName);
    if (aValue instanceof String [])
    {
      // multiple values passed in the request
      return ContainerHelper.newList ((String []) aValue);
    }
    if (aValue instanceof String)
    {
      // single value passed in the request
      return ContainerHelper.newList ((String) aValue);
    }
    // E.g. for file items
    return aDefault;
  }

  @Override
  public boolean hasAttributeValue (@Nullable final String sName, @Nullable final String sDesiredValue)
  {
    return EqualsUtils.equals (getAttributeAsString (sName), sDesiredValue);
  }

  @Override
  public boolean hasAttributeValue (@Nullable final String sName,
                                    @Nullable final String sDesiredValue,
                                    final boolean bDefault)
  {
    final String sValue = getAttributeAsString (sName);
    return sValue == null ? bDefault : EqualsUtils.equals (sValue, sDesiredValue);
  }

  /**
   * Returns the name of the character encoding used in the body of this
   * request. This method returns <code>null</code> if the request does not
   * specify a character encoding
   * 
   * @return a <code>String</code> containing the name of the character
   *         encoding, or <code>null</code> if the request does not specify a
   *         character encoding
   */
  @Nullable
  public String getCharacterEncoding ()
  {
    return this.m_aHttpRequest.getCharacterEncoding ();
  }

  @Override
  @Nonnull
  @ReturnsMutableCopy
  public Map <String, IFileItem> getAllUploadedFileItems ()
  {
    final Map <String, IFileItem> ret = new HashMap <String, IFileItem> ();
    final Enumeration <String> aEnum = getAttributeNames ();
    while (aEnum.hasMoreElements ())
    {
      final String sAttrName = aEnum.nextElement ();
      final Object aAttrValue = getAttributeObject (sAttrName);
      if (aAttrValue instanceof IFileItem)
      {
        ret.put (sAttrName, (IFileItem) aAttrValue);
      }
    }
    return ret;
  }

  @Override
  @Nonnull
  @ReturnsMutableCopy
  public Map <String, IFileItem []> getAllUploadedFileItemsComplete ()
  {
    final Map <String, IFileItem []> ret = new HashMap <String, IFileItem []> ();
    final Enumeration <String> aEnum = getAttributeNames ();
    while (aEnum.hasMoreElements ())
    {
      final String sAttrName = aEnum.nextElement ();
      final Object aAttrValue = getAttributeObject (sAttrName);
      if (aAttrValue instanceof IFileItem)
      {
        ret.put (sAttrName, new IFileItem [] { (IFileItem) aAttrValue });
      }
      else
        if (aAttrValue instanceof IFileItem [])
        {
          ret.put (sAttrName, ArrayHelper.getCopy ((IFileItem []) aAttrValue));
        }
    }
    return ret;
  }

  @Override
  @Nonnull
  @ReturnsMutableCopy
  public List <IFileItem> getAllUploadedFileItemValues ()
  {
    final List <IFileItem> ret = new ArrayList <IFileItem> ();
    final Enumeration <String> aEnum = getAttributeNames ();
    while (aEnum.hasMoreElements ())
    {
      final String sAttrName = aEnum.nextElement ();
      final Object aAttrValue = getAttributeObject (sAttrName);
      if (aAttrValue instanceof IFileItem)
      {
        ret.add ((IFileItem) aAttrValue);
      }
      else
        if (aAttrValue instanceof IFileItem [])
        {
          for (final IFileItem aChild : (IFileItem []) aAttrValue)
          {
            ret.add (aChild);
          }
        }
    }
    return ret;
  }

  @Override
  @Nullable
  public IFileItem getAttributeAsFileItem (@Nullable final String sAttrName)
  {
    final Object aObject = getAttributeObject (sAttrName);
    return aObject instanceof IFileItem ? (IFileItem) aObject : null;
  }

  @Override
  @Nonnull
  public IRequestParamMap getRequestParamMap ()
  {
    // Check if a value is cached in the scope
    IRequestParamMap aValue = getCastedAttribute (REQUEST_ATTR_REQUESTPARAMMAP);
    if (aValue == null)
    {
      // Use all attributes except the internal ones
      final Map <String, Object> aAttrs = getAllAttributes ();
      aAttrs.remove (REQUEST_ATTR_SCOPE_INITED);
      // Request the map and put it in scope
      aValue = RequestParamMap.create (aAttrs);
      setAttribute (REQUEST_ATTR_REQUESTPARAMMAP, aValue);
    }
    return aValue;
  }

  @Override
  public String getScheme ()
  {
    return this.m_aHttpRequest.getScheme ();
  }

  @Override
  public String getServerName ()
  {
    return this.m_aHttpRequest.getServerName ();
  }

  /**
   * @deprecated Use {@link #getProtocol()} instead
   */
  @Override
  @Deprecated
  public String getServerProtocolVersion ()
  {
    return getProtocol ();
  }

  @Override
  public String getProtocol ()
  {
    return this.m_aHttpRequest.getProtocol ();
  }

  @Override
  @Nullable
  public EHTTPVersion getHttpVersion ()
  {
    return RequestHelper.getHttpVersion (this.m_aHttpRequest);
  }

  @Override
  public int getServerPort ()
  {
    return this.m_aHttpRequest.getServerPort ();
  }

  @Override
  public String getMethod ()
  {
    return this.m_aHttpRequest.getMethod ();
  }

  @Override
  @Nullable
  public EHTTPMethod getHttpMethod ()
  {
    return RequestHelper.getHttpMethod (this.m_aHttpRequest);
  }

  @Override
  @Nullable
  public String getPathInfo ()
  {
    return RequestHelper.getPathInfo (this.m_aHttpRequest);
  }

  @Override
  @Nonnull
  public String getPathWithinServletContext ()
  {
    return RequestHelper.getPathWithinServletContext (this.m_aHttpRequest);
  }

  @Override
  @Nonnull
  public String getPathWithinServlet ()
  {
    return RequestHelper.getPathWithinServlet (this.m_aHttpRequest);
  }

  @Override
  public String getPathTranslated ()
  {
    return this.m_aHttpRequest.getPathTranslated ();
  }

  @Override
  public String getQueryString ()
  {
    return this.m_aHttpRequest.getQueryString ();
  }

  @Override
  public String getRemoteHost ()
  {
    return this.m_aHttpRequest.getRemoteHost ();
  }

  @Override
  public String getRemoteAddr ()
  {
    return this.m_aHttpRequest.getRemoteAddr ();
  }

  @Override
  public String getAuthType ()
  {
    return this.m_aHttpRequest.getAuthType ();
  }

  @Override
  public String getRemoteUser ()
  {
    return this.m_aHttpRequest.getRemoteUser ();
  }

  @Override
  public String getContentType ()
  {
    return this.m_aHttpRequest.getContentType ();
  }

  @Override
  public long getContentLength ()
  {
    return RequestHelper.getContentLength (this.m_aHttpRequest);
  }

  @Override
  @Nonnull
  public String getRequestURI ()
  {
    return RequestHelper.getRequestURI (this.m_aHttpRequest);
  }

  @Override
  @Nonnull
  public String getServletPath ()
  {
    return this.m_aHttpRequest.getServletPath ();
  }

  @Override
  public HttpSession getSession (final boolean bCreateIfNotExisting)
  {
    return this.m_aHttpRequest.getSession (bCreateIfNotExisting);
  }

  @Nonnull
  private StringBuilder _getFullServerPath ()
  {
    return RequestHelper.getFullServerName (this.m_aHttpRequest.getScheme (),
                                            this.m_aHttpRequest.getServerName (),
                                            this.m_aHttpRequest.getServerPort ());
  }

  @Override
  @Nonnull
  public String getFullServerPath ()
  {
    return _getFullServerPath ().toString ();
  }

  @Override
  @Nonnull
  public String getContextPath ()
  {
    return this.m_aHttpRequest.getContextPath ();
  }

  @Override
  @Nonnull
  public String getFullContextPath ()
  {
    return _getFullServerPath ().append (getContextPath ()).toString ();
  }

  /**
   * This is a heuristic method to determine whether a request is for a file
   * (e.g. x.jsp) or for a servlet. It is assumed that regular servlets don't
   * have a '.' in their name!
   * 
   * @param sServletPath
   *        The non-<code>null</code> servlet path to check
   * @return <code>true</code> if it is assumed that the request is file based,
   *         <code>false</code> if it can be assumed to be a regular servlet.
   */
  public static boolean isFileBasedRequest (@Nonnull final String sServletPath)
  {
    return sServletPath.indexOf ('.') >= 0;
  }

  @Override
  @Nonnull
  public String getContextAndServletPath ()
  {
    final String sServletPath = getServletPath ();
    if (isFileBasedRequest (sServletPath))
    {
      return getContextPath () + sServletPath;
    }
    // For servlets that are not files, we need to append a trailing slash
    return getContextPath () + sServletPath + '/';
  }

  @Override
  @Nonnull
  public String getFullContextAndServletPath ()
  {
    final String sServletPath = getServletPath ();
    if (isFileBasedRequest (sServletPath))
    {
      return getFullContextPath () + sServletPath;
    }
    // For servlets, we need to append a trailing slash
    return getFullContextPath () + sServletPath + '/';
  }

  @Override
  @Nonnull
  @Nonempty
  public String getURL ()
  {
    return RequestHelper.getURL (this.m_aHttpRequest);
  }

  @Override
  @Nonnull
  public String encodeURL (@Nonnull final String sURL)
  {
    return this.m_aHttpResponse.encodeURL (sURL);
  }

  @Override
  @Nonnull
  public ISimpleURL encodeURL (@Nonnull final ISimpleURL aURL)
  {
    ValueEnforcer.notNull (aURL, "URL"); //$NON-NLS-1$

    // Encode only the path and copy params and anchor
    return new SimpleURL (encodeURL (aURL.getPath ()), aURL.getAllParams (), aURL.getAnchor ());
  }

  @Override
  @Nonnull
  public String encodeRedirectURL (@Nonnull final String sURL)
  {
    return this.m_aHttpResponse.encodeRedirectURL (sURL);
  }

  @Override
  @Nonnull
  public ISimpleURL encodeRedirectURL (@Nonnull final ISimpleURL aURL)
  {
    ValueEnforcer.notNull (aURL, "URL"); //$NON-NLS-1$

    // Encode only the path and copy params and anchor
    return new SimpleURL (encodeRedirectURL (aURL.getPath ()), aURL.getAllParams (), aURL.getAnchor ());
  }

  @Override
  public boolean areCookiesEnabled ()
  {
    // Just check whether the session ID is appended to the URL or not
    return "a".equals (encodeURL ("a")); //$NON-NLS-1$ //$NON-NLS-2$
  }

  @Override
  @Nullable
  public String getRequestHeader (@Nullable final String sName)
  {
    return this.m_aHttpRequest.getHeader (sName);
  }

  @Override
  @Nullable
  public Enumeration <String> getRequestHeaders (@Nullable final String sName)
  {
    return RequestHelper.getRequestHeaders (this.m_aHttpRequest, sName);
  }

  @Override
  @Nullable
  public Enumeration <String> getRequestHeaderNames ()
  {
    return RequestHelper.getRequestHeaderNames (this.m_aHttpRequest);
  }

  @Override
  @Nonnull
  public HttpServletRequest getRequest ()
  {
    return this.m_aHttpRequest;
  }

  @Override
  @Nonnull
  public HttpServletResponse getResponse ()
  {
    return this.m_aHttpResponse;
  }

  @Override
  @Nonnull
  public OutputStream getOutputStream () throws IOException
  {
    return this.m_aHttpResponse.getOutputStream ();
  }

  @Override
  public String toString ()
  {
    return ToStringGenerator.getDerived (super.toString ())
                            .append ("httpRequest", this.m_aHttpRequest) //$NON-NLS-1$
                            .append ("httpResponse", this.m_aHttpResponse) //$NON-NLS-1$
                            .toString ();
  }
}
