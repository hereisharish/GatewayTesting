package com.lyft.data.proxyserver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;


import lombok.extern.slf4j.Slf4j;

import org.apache.commons.io.IOUtils;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentProvider;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.util.InputStreamContentProvider;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.proxy.ProxyServlet;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ssl.SslContextFactory;

@Slf4j
public class ProxyServletImpl extends ProxyServlet.Transparent {
  private ProxyHandler proxyHandler;
  private ProxyServerConfiguration serverConfig;
  public static final String V1_STATEMENT_PATH = "/v1/statement";

  public void setProxyHandler(ProxyHandler proxyHandler) {
    this.proxyHandler = proxyHandler;
    // This needs to be high as external clients may take longer to connect.
    this.setTimeout(TimeUnit.MINUTES.toMillis(1));
  }

  public void setServerConfig(ProxyServerConfiguration config) {
    this.serverConfig = config;
  }

  // Overriding this method to support ssl
  @Override
  protected HttpClient newHttpClient() {
    SslContextFactory sslFactory = new SslContextFactory();

    if (serverConfig != null && serverConfig.isForwardKeystore()) {
      sslFactory.setKeyStorePath(serverConfig.getKeystorePath());
      sslFactory.setKeyStorePassword(serverConfig.getKeystorePass());
    } else {
      sslFactory.setTrustAll(true);
    }
    sslFactory.setStopTimeout(TimeUnit.SECONDS.toMillis(15));
    sslFactory.setSslSessionTimeout((int) TimeUnit.SECONDS.toMillis(15));

    HttpClient httpClient = new HttpClient(sslFactory);
    httpClient.setMaxConnectionsPerDestination(10000);
    httpClient.setConnectTimeout(TimeUnit.SECONDS.toMillis(60));
    return httpClient;
  }

  /** Customize the headers of forwarding proxy requests. */
  @Override
  protected void addProxyHeaders(HttpServletRequest request, Request proxyRequest) {
	  String text = "";
	  try {
	    text = IOUtils.toString(request.getInputStream(), StandardCharsets.UTF_8.name());
	} catch (IOException e) {
		// TODO Auto-generated catch block
		e.printStackTrace();
	}
	  
//	  if(text.startsWith("select") || text.startsWith("SELECT")) {
//		  proxyRequest.getHeaders().remove("Content-Length");
//		  proxyRequest.header("Content-Length", String.valueOf("Select count(*) as harish from system.runtime.nodes".length()));
//	  }
	  
      
    super.addProxyHeaders(request, proxyRequest);    
    if (proxyHandler != null) {
      proxyHandler.preConnectionHook(request, proxyRequest);
    }
  }

  
  @Override
  protected String rewriteTarget(HttpServletRequest request) {
    String target = null;
    if (proxyHandler != null) {
      target = proxyHandler.rewriteTarget(request);
    }
    if (target == null) {
      target = super.rewriteTarget(request);
    }
    log.debug("Target : " + target);
    return target;
  }

  @Override
  protected ContentProvider proxyRequestContent(HttpServletRequest request, HttpServletResponse response, Request proxyRequest) throws IOException
  {
      //return new ProxyInputStreamContentProvider(request, response, proxyRequest, request.getInputStream());
      //return new ProxyInputStreamContentProvider(request, response, proxyRequest, IOUtils.toInputStream("Select count(*) as harish from system.runtime.nodes"));
	  String text = "";
	  try {
	    text = IOUtils.toString(request.getInputStream(), StandardCharsets.UTF_8.name());
	    log.info("input query ==> " + text);
	} catch (IOException e) {
		// TODO Auto-generated catch block
		e.printStackTrace();
	}
	  
	  String requestPath = request.getRequestURI();
      if (requestPath.startsWith(V1_STATEMENT_PATH)
          && request.getMethod().equals("POST")) {
    	  //String sampleSelect = "Select 'query re-writing works garry',node_id as newCol from system.runtime.nodes";
    	  String USER_HEADER = "X-Trino-User";
    	  String ALTERNATE_USER_HEADER = "X-Presto-User";
    	  String user = Optional.ofNullable(request.getHeader(USER_HEADER))
                  .orElse(request.getHeader(ALTERNATE_USER_HEADER));
    	  String sampleSelect = rewriteQuery(user, text);

    	  proxyRequest.getHeaders().remove("Content-Length");
		  proxyRequest.header("Content-Length", String.valueOf(sampleSelect.length()));
		  return new StringContentProvider(sampleSelect);
      }
      return new ProxyInputStreamContentProvider(request, response, proxyRequest, request.getInputStream());
  }
  
  protected class ProxyInputStreamContentProvider extends InputStreamContentProvider
  {
      private final HttpServletResponse response;
      private final Request proxyRequest;
      private final HttpServletRequest request;

      protected ProxyInputStreamContentProvider(HttpServletRequest request, HttpServletResponse response, Request proxyRequest, InputStream input)
      {
          super(input);
          this.request = request;
          this.response = response;
          this.proxyRequest = proxyRequest;
      }

      @Override
      public long getLength()
      {
          return request.getContentLength();
      }

      @Override
      protected ByteBuffer onRead(byte[] buffer, int offset, int length)
      {
          if (_log.isDebugEnabled())
              _log.debug("{} proxying content to upstream: {} bytes", getRequestId(request), length);
          return onRequestContent(request, proxyRequest, buffer, offset, length);
      }

      protected ByteBuffer onRequestContent(HttpServletRequest request, Request proxyRequest, byte[] buffer, int offset, int length)
      {
          return super.onRead(buffer, offset, length);
      }

      @Override
      protected void onReadFailure(Throwable failure)
      {
          onClientRequestFailure(request, proxyRequest, response, failure);
      }
  }
  
  /**
   * Customize the response returned from remote server.
   *
   * @param request
   * @param response
   * @param proxyResponse
   * @param buffer
   * @param offset
   * @param length
   * @param callback
   */
  protected void onResponseContent(
      HttpServletRequest request,
      HttpServletResponse response,
      Response proxyResponse,
      byte[] buffer,
      int offset,
      int length,
      Callback callback) {
	  log.debug("repsone blah !!!");
    try {
      if (this._log.isDebugEnabled()) {
        this._log.debug(
            "[{}] proxying content to downstream: [{}] bytes", this.getRequestId(request), length);
      }
      if (this.proxyHandler != null) {
        proxyHandler.postConnectionHook(request, response, buffer, offset, length, callback);
      } else {
        super.onResponseContent(request, response, proxyResponse, buffer, offset, length, callback);
      }
    } catch (Throwable var9) {
      callback.failed(var9);
    }
  }
  
  
  protected String rewriteQuery(String user, String inputQuery) {
	  String queryAfterReWrite = inputQuery;
	  if(user.equalsIgnoreCase("thor")) {
		  if(inputQuery.startsWith("select")) {
			  queryAfterReWrite = "select 'xxxxmaskedxxxx' node_id,http_uri,node_version,coordinator,state from system.runtime.nodes";
		  } else  if(inputQuery.startsWith("explain")) {
			  queryAfterReWrite = "explain select 'xxxxmaskedxxxx' node_id,http_uri,node_version,coordinator,state from system.runtime.nodes";
		  }
	  }
	  return queryAfterReWrite;
  }
}
