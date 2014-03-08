/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. See accompanying LICENSE file.
 */
package org.apache.hadoop.security.authentication.server;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Properties;
import java.util.Vector;

import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.hadoop.security.authentication.client.AuthenticatedURL;
import org.apache.hadoop.security.authentication.client.AuthenticationException;
import org.apache.hadoop.security.authentication.util.Signer;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

public class TestAuthenticationFilter {

  @Test
  public void testGetConfiguration() throws Exception {
    AuthenticationFilter filter = new AuthenticationFilter();
    FilterConfig config = Mockito.mock(FilterConfig.class);
    Mockito.when(config.getInitParameter(AuthenticationFilter.CONFIG_PREFIX)).thenReturn("");
    Mockito.when(config.getInitParameter("a")).thenReturn("A");
    Mockito.when(config.getInitParameterNames()).thenReturn(new Vector<String>(Arrays.asList("a")).elements());
    Properties props = filter.getConfiguration("", config);
    Assert.assertEquals("A", props.getProperty("a"));

    config = Mockito.mock(FilterConfig.class);
    Mockito.when(config.getInitParameter(AuthenticationFilter.CONFIG_PREFIX)).thenReturn("foo");
    Mockito.when(config.getInitParameter("foo.a")).thenReturn("A");
    Mockito.when(config.getInitParameterNames()).thenReturn(new Vector<String>(Arrays.asList("foo.a")).elements());
    props = filter.getConfiguration("foo.", config);
    Assert.assertEquals("A", props.getProperty("a"));
  }

  @Test
  public void testInitEmpty() throws Exception {
    AuthenticationFilter filter = new AuthenticationFilter();
    try {
      FilterConfig config = Mockito.mock(FilterConfig.class);
      Mockito.when(config.getInitParameterNames()).thenReturn(new Vector<String>().elements());
      filter.init(config);
      Assert.fail();
    } catch (ServletException ex) {
      // Expected
    } catch (Exception ex) {
      Assert.fail();
    } finally {
      filter.destroy();
    }
  }

  public static class DummyAuthenticationHandler implements AuthenticationHandler {
    public static boolean init;
    public static boolean managementOperationReturn;
    public static boolean destroy;
    public static boolean expired;

    public static final String TYPE = "dummy";

    public static void reset() {
      init = false;
      destroy = false;
    }

    @Override
    public void init(Properties config) throws ServletException {
      init = true;
      managementOperationReturn =
        config.getProperty("management.operation.return", "true").equals("true");
      expired = config.getProperty("expired.token", "false").equals("true");
    }

    @Override
    public boolean managementOperation(AuthenticationToken token,
                                       HttpServletRequest request,
                                       HttpServletResponse response)
      throws IOException, AuthenticationException {
      if (!managementOperationReturn) {
        response.setStatus(HttpServletResponse.SC_ACCEPTED);
      }
      return managementOperationReturn;
    }

    @Override
    public void destroy() {
      destroy = true;
    }

    @Override
    public String getType() {
      return TYPE;
    }

    @Override
    public AuthenticationToken authenticate(HttpServletRequest request, HttpServletResponse response)
      throws IOException, AuthenticationException {
      AuthenticationToken token = null;
      String param = request.getParameter("authenticated");
      if (param != null && param.equals("true")) {
        token = new AuthenticationToken("u", "p", "t");
        token.setExpires((expired) ? 0 : System.currentTimeMillis() + 1000);
      } else {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      }
      return token;
    }
  }

  @Test
  public void testInit() throws Exception {

    // minimal configuration & simple auth handler (Pseudo)
    AuthenticationFilter filter = new AuthenticationFilter();
    try {
      FilterConfig config = Mockito.mock(FilterConfig.class);
      Mockito.when(config.getInitParameter(AuthenticationFilter.AUTH_TYPE)).thenReturn("simple");
      Mockito.when(config.getInitParameter(AuthenticationFilter.AUTH_TOKEN_VALIDITY)).thenReturn("1000");
      Mockito.when(config.getInitParameterNames()).thenReturn(
        new Vector<String>(Arrays.asList(AuthenticationFilter.AUTH_TYPE,
                                 AuthenticationFilter.AUTH_TOKEN_VALIDITY)).elements());
      filter.init(config);
      Assert.assertEquals(PseudoAuthenticationHandler.class, filter.getAuthenticationHandler().getClass());
      Assert.assertTrue(filter.isRandomSecret());
      Assert.assertNull(filter.getCookieDomain());
      Assert.assertNull(filter.getCookiePath());
      Assert.assertEquals(1000, filter.getValidity());
    } finally {
      filter.destroy();
    }

    // custom secret
    filter = new AuthenticationFilter();
    try {
      FilterConfig config = Mockito.mock(FilterConfig.class);
      Mockito.when(config.getInitParameter(AuthenticationFilter.AUTH_TYPE)).thenReturn("simple");
      Mockito.when(config.getInitParameter(AuthenticationFilter.SIGNATURE_SECRET)).thenReturn("secret");
      Mockito.when(config.getInitParameterNames()).thenReturn(
        new Vector<String>(Arrays.asList(AuthenticationFilter.AUTH_TYPE,
                                 AuthenticationFilter.SIGNATURE_SECRET)).elements());
      filter.init(config);
      Assert.assertFalse(filter.isRandomSecret());
    } finally {
      filter.destroy();
    }

    // custom cookie domain and cookie path
    filter = new AuthenticationFilter();
    try {
      FilterConfig config = Mockito.mock(FilterConfig.class);
      Mockito.when(config.getInitParameter(AuthenticationFilter.AUTH_TYPE)).thenReturn("simple");
      Mockito.when(config.getInitParameter(AuthenticationFilter.COOKIE_DOMAIN)).thenReturn(".foo.com");
      Mockito.when(config.getInitParameter(AuthenticationFilter.COOKIE_PATH)).thenReturn("/bar");
      Mockito.when(config.getInitParameterNames()).thenReturn(
        new Vector<String>(Arrays.asList(AuthenticationFilter.AUTH_TYPE,
                                 AuthenticationFilter.COOKIE_DOMAIN,
                                 AuthenticationFilter.COOKIE_PATH)).elements());
      filter.init(config);
      Assert.assertEquals(".foo.com", filter.getCookieDomain());
      Assert.assertEquals("/bar", filter.getCookiePath());
    } finally {
      filter.destroy();
    }

    // authentication handler lifecycle, and custom impl
    DummyAuthenticationHandler.reset();
    filter = new AuthenticationFilter();
    try {
      FilterConfig config = Mockito.mock(FilterConfig.class);
      Mockito.when(config.getInitParameter("management.operation.return")).
        thenReturn("true");
      Mockito.when(config.getInitParameter(AuthenticationFilter.AUTH_TYPE)).thenReturn(
        DummyAuthenticationHandler.class.getName());
      Mockito.when(config.getInitParameterNames()).thenReturn(
        new Vector<String>(
          Arrays.asList(AuthenticationFilter.AUTH_TYPE,
                        "management.operation.return")).elements());
      filter.init(config);
      Assert.assertTrue(DummyAuthenticationHandler.init);
    } finally {
      filter.destroy();
      Assert.assertTrue(DummyAuthenticationHandler.destroy);
    }

    // kerberos auth handler
    filter = new AuthenticationFilter();
    try {
      FilterConfig config = Mockito.mock(FilterConfig.class);
      Mockito.when(config.getInitParameter(AuthenticationFilter.AUTH_TYPE)).thenReturn("kerberos");
      Mockito.when(config.getInitParameterNames()).thenReturn(
        new Vector<String>(Arrays.asList(AuthenticationFilter.AUTH_TYPE)).elements());
      filter.init(config);
    } catch (ServletException ex) {
      // Expected
    } finally {
      Assert.assertEquals(KerberosAuthenticationHandler.class, filter.getAuthenticationHandler().getClass());
      filter.destroy();
    }
  }

  @Test
  public void testGetRequestURL() throws Exception {
    AuthenticationFilter filter = new AuthenticationFilter();
    try {
      FilterConfig config = Mockito.mock(FilterConfig.class);
      Mockito.when(config.getInitParameter("management.operation.return")).
        thenReturn("true");
      Mockito.when(config.getInitParameter(AuthenticationFilter.AUTH_TYPE)).thenReturn(
        DummyAuthenticationHandler.class.getName());
      Mockito.when(config.getInitParameterNames()).thenReturn(
        new Vector<String>(
          Arrays.asList(AuthenticationFilter.AUTH_TYPE,
                        "management.operation.return")).elements());
      filter.init(config);

      HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
      Mockito.when(request.getRequestURL()).thenReturn(new StringBuffer("http://foo:8080/bar"));
      Mockito.when(request.getQueryString()).thenReturn("a=A&b=B");

      Assert.assertEquals("http://foo:8080/bar?a=A&b=B", filter.getRequestURL(request));
    } finally {
      filter.destroy();
    }
  }

  @Test
  public void testGetToken() throws Exception {
    AuthenticationFilter filter = new AuthenticationFilter();
    try {
      FilterConfig config = Mockito.mock(FilterConfig.class);
      Mockito.when(config.getInitParameter("management.operation.return")).
        thenReturn("true");
      Mockito.when(config.getInitParameter(AuthenticationFilter.AUTH_TYPE)).thenReturn(
        DummyAuthenticationHandler.class.getName());
      Mockito.when(config.getInitParameter(AuthenticationFilter.SIGNATURE_SECRET)).thenReturn("secret");
      Mockito.when(config.getInitParameterNames()).thenReturn(
        new Vector<String>(
          Arrays.asList(AuthenticationFilter.AUTH_TYPE,
                        AuthenticationFilter.SIGNATURE_SECRET,
                        "management.operation.return")).elements());
      filter.init(config);

      AuthenticationToken token = new AuthenticationToken("u", "p", DummyAuthenticationHandler.TYPE);
      token.setExpires(System.currentTimeMillis() + 1000);
      Signer signer = new Signer("secret".getBytes());
      String tokenSigned = signer.sign(token.toString());

      Cookie cookie = new Cookie(AuthenticatedURL.AUTH_COOKIE, tokenSigned);
      HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
      Mockito.when(request.getCookies()).thenReturn(new Cookie[]{cookie});

      AuthenticationToken newToken = filter.getToken(request);

      Assert.assertEquals(token.toString(), newToken.toString());
    } finally {
      filter.destroy();
    }
  }

  @Test
  public void testGetTokenExpired() throws Exception {
    AuthenticationFilter filter = new AuthenticationFilter();
    try {
      FilterConfig config = Mockito.mock(FilterConfig.class);
      Mockito.when(config.getInitParameter("management.operation.return")).thenReturn("true");
      Mockito.when(config.getInitParameter(AuthenticationFilter.AUTH_TYPE)).thenReturn(
        DummyAuthenticationHandler.class.getName());
      Mockito.when(config.getInitParameter(AuthenticationFilter.SIGNATURE_SECRET)).thenReturn("secret");
      Mockito.when(config.getInitParameterNames()).thenReturn(
        new Vector<String>(
          Arrays.asList(AuthenticationFilter.AUTH_TYPE,
                        AuthenticationFilter.SIGNATURE_SECRET,
                        "management.operation.return")).elements());
      filter.init(config);

      AuthenticationToken token = new AuthenticationToken("u", "p", "invalidtype");
      token.setExpires(System.currentTimeMillis() - 1000);
      Signer signer = new Signer("secret".getBytes());
      String tokenSigned = signer.sign(token.toString());

      Cookie cookie = new Cookie(AuthenticatedURL.AUTH_COOKIE, tokenSigned);
      HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
      Mockito.when(request.getCookies()).thenReturn(new Cookie[]{cookie});

      try {
        filter.getToken(request);
        Assert.fail();
      } catch (AuthenticationException ex) {
        // Expected
      } catch (Exception ex) {
        Assert.fail();
      }
    } finally {
      filter.destroy();
    }
  }

  @Test
  public void testGetTokenInvalidType() throws Exception {
    AuthenticationFilter filter = new AuthenticationFilter();
    try {
      FilterConfig config = Mockito.mock(FilterConfig.class);
      Mockito.when(config.getInitParameter("management.operation.return")).
        thenReturn("true");
      Mockito.when(config.getInitParameter(AuthenticationFilter.AUTH_TYPE)).thenReturn(
        DummyAuthenticationHandler.class.getName());
      Mockito.when(config.getInitParameter(AuthenticationFilter.SIGNATURE_SECRET)).thenReturn("secret");
      Mockito.when(config.getInitParameterNames()).thenReturn(
        new Vector<String>(
          Arrays.asList(AuthenticationFilter.AUTH_TYPE,
                        AuthenticationFilter.SIGNATURE_SECRET,
                        "management.operation.return")).elements());
      filter.init(config);

      AuthenticationToken token = new AuthenticationToken("u", "p", "invalidtype");
      token.setExpires(System.currentTimeMillis() + 1000);
      Signer signer = new Signer("secret".getBytes());
      String tokenSigned = signer.sign(token.toString());

      Cookie cookie = new Cookie(AuthenticatedURL.AUTH_COOKIE, tokenSigned);
      HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
      Mockito.when(request.getCookies()).thenReturn(new Cookie[]{cookie});

      try {
        filter.getToken(request);
        Assert.fail();
      } catch (AuthenticationException ex) {
        // Expected
      } catch (Exception ex) {
        Assert.fail();
      }
    } finally {
      filter.destroy();
    }
  }

  @Test
  public void testDoFilterNotAuthenticated() throws Exception {
    AuthenticationFilter filter = new AuthenticationFilter();
    try {
      FilterConfig config = Mockito.mock(FilterConfig.class);
      Mockito.when(config.getInitParameter("management.operation.return")).
        thenReturn("true");
      Mockito.when(config.getInitParameter(AuthenticationFilter.AUTH_TYPE)).thenReturn(
        DummyAuthenticationHandler.class.getName());
      Mockito.when(config.getInitParameterNames()).thenReturn(
        new Vector<String>(
          Arrays.asList(AuthenticationFilter.AUTH_TYPE,
                        "management.operation.return")).elements());
      filter.init(config);

      HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
      Mockito.when(request.getRequestURL()).thenReturn(new StringBuffer("http://foo:8080/bar"));

      HttpServletResponse response = Mockito.mock(HttpServletResponse.class);

      FilterChain chain = Mockito.mock(FilterChain.class);

      Mockito.doAnswer(
        new Answer<Object>() {
          @Override
          public Object answer(InvocationOnMock invocation) throws Throwable {
            Assert.fail();
            return null;
          }
        }
      ).when(chain).doFilter(Mockito.<ServletRequest>anyObject(), Mockito.<ServletResponse>anyObject());

      filter.doFilter(request, response, chain);

      Mockito.verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    } finally {
      filter.destroy();
    }
  }

  private void _testDoFilterAuthentication(boolean withDomainPath,
                                           boolean invalidToken,
                                           boolean expired) throws Exception {
    AuthenticationFilter filter = new AuthenticationFilter();
    FilterConfig config = Mockito.mock(FilterConfig.class);
    Mockito.when(config.getInitParameter("management.operation.return")).
            thenReturn("true");
    Mockito.when(config.getInitParameter("expired.token")).
            thenReturn(Boolean.toString(expired));
    Mockito.when(config.getInitParameter(AuthenticationFilter.AUTH_TYPE))
            .thenReturn(DummyAuthenticationHandler.class.getName());
    Mockito.when(config.getInitParameter(AuthenticationFilter
            .AUTH_TOKEN_VALIDITY)).thenReturn("1000");
    Mockito.when(config.getInitParameter(AuthenticationFilter
            .SIGNATURE_SECRET)).thenReturn("secret");
    Mockito.when(config.getInitParameterNames()).thenReturn(new
            Vector<String>(Arrays.asList(AuthenticationFilter.AUTH_TYPE,
            AuthenticationFilter.AUTH_TOKEN_VALIDITY,
            AuthenticationFilter.SIGNATURE_SECRET, "management.operation" +
            ".return", "expired.token")).elements());

    if (withDomainPath) {
      Mockito.when(config.getInitParameter(AuthenticationFilter
              .COOKIE_DOMAIN)).thenReturn(".foo.com");
      Mockito.when(config.getInitParameter(AuthenticationFilter.COOKIE_PATH))
              .thenReturn("/bar");
      Mockito.when(config.getInitParameterNames()).thenReturn(new
              Vector<String>(Arrays.asList(AuthenticationFilter.AUTH_TYPE,
              AuthenticationFilter.AUTH_TOKEN_VALIDITY,
              AuthenticationFilter.SIGNATURE_SECRET,
              AuthenticationFilter.COOKIE_DOMAIN, AuthenticationFilter
              .COOKIE_PATH, "management.operation.return")).elements());
    }

    HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
    Mockito.when(request.getParameter("authenticated")).thenReturn("true");
    Mockito.when(request.getRequestURL()).thenReturn(new StringBuffer
            ("http://foo:8080/bar"));
    Mockito.when(request.getQueryString()).thenReturn("authenticated=true");

    if (invalidToken) {
      Mockito.when(request.getCookies()).thenReturn(new Cookie[]{new Cookie
              (AuthenticatedURL.AUTH_COOKIE, "foo")});
    }

    HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
    FilterChain chain = Mockito.mock(FilterChain.class);

    final HashMap<String, String> cookieMap = new HashMap<String, String>();
    Mockito.doAnswer(new Answer<Object>() {
      @Override
      public Object answer(InvocationOnMock invocation) throws Throwable {
        String cookieHeader = (String)invocation.getArguments()[1];
        parseCookieMap(cookieHeader, cookieMap);
        return null;
      }
    }).when(response).addHeader(Mockito.eq("Set-Cookie"), Mockito.anyString());

    try {
      filter.init(config);
      filter.doFilter(request, response, chain);

      if (expired) {
        Mockito.verify(response, Mockito.never()).
          addCookie(Mockito.any(Cookie.class));
      } else {
        String v = cookieMap.get(AuthenticatedURL.AUTH_COOKIE);
        Assert.assertNotNull(v);
        Assert.assertTrue(v.contains("u=") && v.contains("p=") && v.contains
                ("t=") && v.contains("e=") && v.contains("s="));
        Mockito.verify(chain).doFilter(Mockito.any(ServletRequest.class),
                Mockito.any(ServletResponse.class));

        Signer signer = new Signer("secret".getBytes());
        String value = signer.verifyAndExtract(v);
        AuthenticationToken token = AuthenticationToken.parse(value);
        Assert.assertEquals(System.currentTimeMillis() + 1000 * 1000,
                     token.getExpires(), 100);

        if (withDomainPath) {
          Assert.assertEquals(".foo.com", cookieMap.get("Domain"));
          Assert.assertEquals("/bar", cookieMap.get("Path"));
        } else {
          Assert.assertFalse(cookieMap.containsKey("Domain"));
          Assert.assertFalse(cookieMap.containsKey("Path"));
        }
      }
    } finally {
      filter.destroy();
    }
  }

  private static void parseCookieMap(String cookieHeader, HashMap<String,
          String> cookieMap) {
    for (String pair : cookieHeader.split(";")) {
      String p = pair.trim();
      int idx = p.indexOf('=');
      final String k, v;
      if (idx == -1) {
        k = p;
        v = null;
      } else if (idx == p.length()) {
        k = p.substring(0, idx - 1);
        v = null;
      } else {
        k = p.substring(0, idx);
        v = p.substring(idx + 1);
      }
      cookieMap.put(k, v);
    }
  }

  @Test
  public void testDoFilterAuthentication() throws Exception {
    _testDoFilterAuthentication(false, false, false);
  }

  @Test
  public void testDoFilterAuthenticationImmediateExpiration() throws Exception {
    _testDoFilterAuthentication(false, false, true);
  }

  @Test
  public void testDoFilterAuthenticationWithInvalidToken() throws Exception {
    _testDoFilterAuthentication(false, true, false);
  }

  @Test
  public void testDoFilterAuthenticationWithDomainPath() throws Exception {
    _testDoFilterAuthentication(true, false, false);
  }

  @Test
  public void testDoFilterAuthenticated() throws Exception {
    AuthenticationFilter filter = new AuthenticationFilter();
    try {
      FilterConfig config = Mockito.mock(FilterConfig.class);
      Mockito.when(config.getInitParameter("management.operation.return")).
        thenReturn("true");
      Mockito.when(config.getInitParameter(AuthenticationFilter.AUTH_TYPE)).thenReturn(
        DummyAuthenticationHandler.class.getName());
      Mockito.when(config.getInitParameterNames()).thenReturn(
        new Vector<String>(
          Arrays.asList(AuthenticationFilter.AUTH_TYPE,
                        "management.operation.return")).elements());
      filter.init(config);

      HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
      Mockito.when(request.getRequestURL()).thenReturn(new StringBuffer("http://foo:8080/bar"));

      AuthenticationToken token = new AuthenticationToken("u", "p", "t");
      token.setExpires(System.currentTimeMillis() + 1000);
      Signer signer = new Signer("secret".getBytes());
      String tokenSigned = signer.sign(token.toString());

      Cookie cookie = new Cookie(AuthenticatedURL.AUTH_COOKIE, tokenSigned);
      Mockito.when(request.getCookies()).thenReturn(new Cookie[]{cookie});

      HttpServletResponse response = Mockito.mock(HttpServletResponse.class);

      FilterChain chain = Mockito.mock(FilterChain.class);

      Mockito.doAnswer(
        new Answer<Object>() {
          @Override
          public Object answer(InvocationOnMock invocation) throws Throwable {
            Object[] args = invocation.getArguments();
            HttpServletRequest request = (HttpServletRequest) args[0];
            Assert.assertEquals("u", request.getRemoteUser());
            Assert.assertEquals("p", request.getUserPrincipal().getName());
            return null;
          }
        }
      ).when(chain).doFilter(Mockito.<ServletRequest>anyObject(), Mockito.<ServletResponse>anyObject());

      filter.doFilter(request, response, chain);

    } finally {
      filter.destroy();
    }
  }

  @Test
  public void testDoFilterAuthenticatedExpired() throws Exception {
    AuthenticationFilter filter = new AuthenticationFilter();
    try {
      FilterConfig config = Mockito.mock(FilterConfig.class);
      Mockito.when(config.getInitParameter("management.operation.return")).
        thenReturn("true");
      Mockito.when(config.getInitParameter(AuthenticationFilter.AUTH_TYPE)).thenReturn(
        DummyAuthenticationHandler.class.getName());
      Mockito.when(config.getInitParameterNames()).thenReturn(
        new Vector<String>(
          Arrays.asList(AuthenticationFilter.AUTH_TYPE,
                        "management.operation.return")).elements());
      filter.init(config);

      HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
      Mockito.when(request.getRequestURL()).thenReturn(new StringBuffer("http://foo:8080/bar"));

      AuthenticationToken token = new AuthenticationToken("u", "p", DummyAuthenticationHandler.TYPE);
      token.setExpires(System.currentTimeMillis() - 1000);
      Signer signer = new Signer("secret".getBytes());
      String tokenSigned = signer.sign(token.toString());

      Cookie cookie = new Cookie(AuthenticatedURL.AUTH_COOKIE, tokenSigned);
      Mockito.when(request.getCookies()).thenReturn(new Cookie[]{cookie});

      HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
      FilterChain chain = Mockito.mock(FilterChain.class);

      verifyUnauthorized(filter, request, response, chain);
    } finally {
      filter.destroy();
    }
  }

  private static void verifyUnauthorized(AuthenticationFilter filter,
                                         HttpServletRequest request,
                                         HttpServletResponse response,
                                         FilterChain chain) throws
                                                            IOException,
                                                            ServletException {
    final HashMap<String, String> cookieMap = new HashMap<String, String>();
    Mockito.doAnswer(new Answer<Object>() {
      @Override
      public Object answer(InvocationOnMock invocation) throws Throwable {
        String cookieHeader = (String) invocation.getArguments()[1];
        parseCookieMap(cookieHeader, cookieMap);
        return null;
      }
    }).when(response).addHeader(Mockito.eq("Set-Cookie"), Mockito.anyString());

    filter.doFilter(request, response, chain);

    Mockito.verify(response).sendError(Mockito.eq(HttpServletResponse
            .SC_UNAUTHORIZED), Mockito.anyString());
    Mockito.verify(chain, Mockito.never()).doFilter(Mockito.any
            (ServletRequest.class), Mockito.any(ServletResponse.class));

    Assert.assertTrue(cookieMap.containsKey(AuthenticatedURL.AUTH_COOKIE));
    Assert.assertEquals("", cookieMap.get(AuthenticatedURL.AUTH_COOKIE));
  }

  @Test
  public void testDoFilterAuthenticatedInvalidType() throws Exception {
    AuthenticationFilter filter = new AuthenticationFilter();
    try {
      FilterConfig config = Mockito.mock(FilterConfig.class);
      Mockito.when(config.getInitParameter("management.operation.return")).
        thenReturn("true");
      Mockito.when(config.getInitParameter(AuthenticationFilter.AUTH_TYPE)).thenReturn(
        DummyAuthenticationHandler.class.getName());
      Mockito.when(config.getInitParameterNames()).thenReturn(
        new Vector<String>(
          Arrays.asList(AuthenticationFilter.AUTH_TYPE,
                        "management.operation.return")).elements());
      filter.init(config);

      HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
      Mockito.when(request.getRequestURL()).thenReturn(new StringBuffer("http://foo:8080/bar"));

      AuthenticationToken token = new AuthenticationToken("u", "p", "invalidtype");
      token.setExpires(System.currentTimeMillis() + 1000);
      Signer signer = new Signer("secret".getBytes());
      String tokenSigned = signer.sign(token.toString());

      Cookie cookie = new Cookie(AuthenticatedURL.AUTH_COOKIE, tokenSigned);
      Mockito.when(request.getCookies()).thenReturn(new Cookie[]{cookie});

      HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
      FilterChain chain = Mockito.mock(FilterChain.class);

      verifyUnauthorized(filter, request, response, chain);
    } finally {
      filter.destroy();
    }
  }

  @Test
  public void testManagementOperation() throws Exception {
    AuthenticationFilter filter = new AuthenticationFilter();
    try {
      FilterConfig config = Mockito.mock(FilterConfig.class);
      Mockito.when(config.getInitParameter("management.operation.return")).
        thenReturn("false");
      Mockito.when(config.getInitParameter(AuthenticationFilter.AUTH_TYPE)).
        thenReturn(DummyAuthenticationHandler.class.getName());
      Mockito.when(config.getInitParameterNames()).thenReturn(
        new Vector<String>(
          Arrays.asList(AuthenticationFilter.AUTH_TYPE,
                        "management.operation.return")).elements());
      filter.init(config);

      HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
      Mockito.when(request.getRequestURL()).
        thenReturn(new StringBuffer("http://foo:8080/bar"));

      HttpServletResponse response = Mockito.mock(HttpServletResponse.class);

      FilterChain chain = Mockito.mock(FilterChain.class);

      filter.doFilter(request, response, chain);
      Mockito.verify(response).setStatus(HttpServletResponse.SC_ACCEPTED);
      Mockito.verifyNoMoreInteractions(response);

      Mockito.reset(request);
      Mockito.reset(response);

      AuthenticationToken token = new AuthenticationToken("u", "p", "t");
      token.setExpires(System.currentTimeMillis() + 1000);
      Signer signer = new Signer("secret".getBytes());
      String tokenSigned = signer.sign(token.toString());
      Cookie cookie = new Cookie(AuthenticatedURL.AUTH_COOKIE, tokenSigned);
      Mockito.when(request.getCookies()).thenReturn(new Cookie[]{cookie});

      filter.doFilter(request, response, chain);

      Mockito.verify(response).setStatus(HttpServletResponse.SC_ACCEPTED);
      Mockito.verifyNoMoreInteractions(response);

    } finally {
      filter.destroy();
    }
  }

}
