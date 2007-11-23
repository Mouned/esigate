package net.webassembletool;

import java.io.IOException;
import java.io.Writer;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import net.webassembletool.ouput.FileOutput;
import net.webassembletool.ouput.MemoryOutput;
import net.webassembletool.ouput.MultipleOutput;
import net.webassembletool.ouput.Output;
import net.webassembletool.ouput.ResponseOutput;
import net.webassembletool.ouput.StringOutput;
import net.webassembletool.resource.FileResource;
import net.webassembletool.resource.HttpResource;
import net.webassembletool.resource.MemoryResource;
import net.webassembletool.resource.ResourceNotFoundException;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpException;
import org.apache.commons.httpclient.MultiThreadedHttpConnectionManager;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.opensymphony.oscache.base.NeedsRefreshException;
import com.opensymphony.oscache.general.GeneralCacheAdministrator;

/**
 * Main class used to retrieve data from a provider application using HTTP
 * requests. Data can be retrieved as binary streams or as String for text data.
 * To improve performance, the Driver uses a cache that can be configured
 * depending on the needs.
 * 
 * @author Fran�ois-Xavier Bonnet
 * 
 */
public class Driver {
	// TODO remplacer les headers par une classe sp�ciale qui wrape un
	// properties
	// TODO remplacer les manipulations de String par des StringBuilder pour
	// am�liorer les performances
	private final static Log log = LogFactory.getLog(Driver.class);
	private static Driver driver;
	private int cacheRefreshDelay = 0;
	private int cacheMaxFileSize = 0;
	private int timeout = 1000;
	private String baseURL;
	private String localBase;
	private boolean putInCache = false;
	private GeneralCacheAdministrator cache = new GeneralCacheAdministrator();
	private HttpClient httpClient;
	private Driver() {
	}
	/**
	 * Retrieves the default instance of this class that is configured according
	 * to the properties file (driver.properties)
	 * 
	 * @return the default instance
	 */
	public synchronized static Driver getInstance() {
		if (driver == null) {
			driver = new Driver();
			Properties props = new Properties();
			try {
				props.load(Driver.class.getResourceAsStream("driver.properties"));
			} catch (IOException e) {
				throw new ExceptionInInitializerError(e);
			}
			MultiThreadedHttpConnectionManager connectionManager = new MultiThreadedHttpConnectionManager();
			int maxConnectionsPerHost = 20;
			if (props.getProperty("maxConnectionsPerHost") != null)
				maxConnectionsPerHost = Integer.parseInt(props.getProperty("maxConnectionsPerHost"));
			connectionManager.getParams().setDefaultMaxConnectionsPerHost(maxConnectionsPerHost);
			driver.httpClient = new HttpClient(connectionManager);
			driver.baseURL = props.getProperty("remoteUrlBase");
			if (props.getProperty("cacheRefreshDelay") != null)
				driver.cacheRefreshDelay = Integer.parseInt(props.getProperty("cacheRefreshDelay"));
			if (props.getProperty("cacheMaxFileSize") != null)
				driver.cacheMaxFileSize = Integer.parseInt(props.getProperty("cacheMaxFileSize"));
			if (props.getProperty("timeout") != null) {
				driver.timeout = Integer.parseInt(props.getProperty("timeout"));
				driver.httpClient.getParams().setSoTimeout(driver.timeout);
				driver.httpClient.getHttpConnectionManager().getParams().setConnectionTimeout(driver.timeout);
			}
			driver.localBase = props.getProperty("localBase");
			if (props.getProperty("putInCache") != null)
				driver.putInCache = Boolean.parseBoolean(props.getProperty("putInCache"));
		}
		return driver;
	}
	/**
	 * Retrieves a block from the provider application and writes it to a
	 * Writer. Block can be defined in the provider application using HTML
	 * comments.<br />
	 * eg: a block name "myblock" should be delimited with
	 * "&lt;!--$beginblock$myblock$--&gt;" and "&lt;!--$endblock$myblock$--&gt;
	 */
	public void renderBlock(String page, String name, Writer writer) throws IOException {
		String content = getResourceAsString(page);
		String beginString = "<!--$beginblock$" + name + "$-->";
		String endString = "<!--$endblock$" + name + "$-->";
		int begin = content.indexOf(beginString);
		int end = content.indexOf(endString);
		if (begin == -1 || end == -1) {
			content = "";
			log.warn("Block not found: page=" + page + " block=" + name);
		} else {
			content = content.substring(begin + beginString.length(), end);
			log.debug("Serving block: page=" + page + " block=" + name);
		}
		writer.write(content);
	}
	/**
	 * Retrieves a resource from the provider application as binary data and
	 * writes it to the response.
	 * 
	 * @param relUrl
	 *            the relative URL to the resource
	 * @param request
	 *            the request
	 * @param response
	 *            the response
	 * @throws IOException
	 */
	public void renderResource(String relUrl, HttpServletRequest request, HttpServletResponse response) throws IOException {
		try {
			renderResource(relUrl, new ResponseOutput(request, response));
		} catch (ResourceNotFoundException e) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND, "Page not found: " + relUrl);
		}
	}
	/**
	 * Returns a resource from the provider application and writes it to the
	 * output.
	 * 
	 * @throws ResourceNotFoundException
	 * @param relUrl
	 *            the relative URL to the resource
	 * @param output
	 *            the output
	 * @throws IOException
	 * @throws ResourceNotFoundException
	 */
	private void renderResource(String relUrl, Output output) throws IOException, ResourceNotFoundException {
		String httpUrl = getUrlForHttpResource(relUrl);
		String fileUrl = getUrlForFileResource(relUrl);
		MultipleOutput multipleOutput = new MultipleOutput();
		multipleOutput.addOutput(output);
		MemoryResource cachedResource = null;
		try {
			// on charge la resource depuis le cache, m�me p�rim�e
			cachedResource = (MemoryResource) cache.getFromCache(httpUrl);
			cachedResource = (MemoryResource) cache.getFromCache(httpUrl, cacheRefreshDelay);
			if (cachedResource == null)
				throw new NeedsRefreshException(null);
			cachedResource.render(multipleOutput);
		} catch (NeedsRefreshException e) {
			boolean cacheUpdated = false;
			try {
				MemoryOutput memoryOutput = null;
				HttpResource httpResource = getResourceFromHttp(httpUrl);
				if (httpResource != null) {
					memoryOutput = new MemoryOutput(cacheMaxFileSize);
					multipleOutput.addOutput(memoryOutput);
					if (putInCache)
						multipleOutput.addOutput(new FileOutput(fileUrl));
					httpResource.render(multipleOutput);
				} else if (cachedResource != null) {
					cachedResource.render(multipleOutput);
				} else {
					FileResource fileResource = getResourceFromLocal(fileUrl);
					if (fileResource == null)
						throw new ResourceNotFoundException(relUrl);
					memoryOutput = new MemoryOutput(cacheMaxFileSize);
					multipleOutput.addOutput(memoryOutput);
					fileResource.render(multipleOutput);
				}
				if (memoryOutput != null) {
					cachedResource = memoryOutput.toResource();
					cache.putInCache(httpUrl, cachedResource);
					cacheUpdated = true;
				}
			} finally {
				// The resource was not found in cache so osCache has locked
				// this key. We have to remove the lock.
				if (!cacheUpdated)
					cache.cancelUpdate(httpUrl);
			}
		}
	}
	private HttpResource getResourceFromHttp(String url) throws HttpException, IOException {
		HttpResource httpResource = new HttpResource(httpClient, url);
		if (httpResource.exists())
			return httpResource;
		else {
			httpResource.release();
			return null;
		}
	}
	private FileResource getResourceFromLocal(String relUrl) {
		FileResource fileResource = new FileResource(relUrl);
		if (fileResource.exists())
			return fileResource;
		else {
			fileResource.release();
			return null;
		}
	}
	private String getUrlForFileResource(String relUrl) {
		String url = null;
		if (localBase != null && relUrl != null && (localBase.endsWith("/") || localBase.endsWith("\\")) && relUrl.startsWith("/")) {
			url = localBase.substring(0, localBase.length() - 1) + relUrl;
		} else {
			url = localBase + relUrl;
		}
		int index = url.indexOf('?');
		if (index > -1)
			url = url.substring(0, index);
		return url;
	}
	private String getUrlForHttpResource(String relUrl) {
		String url;
		if (baseURL != null && relUrl != null && baseURL.endsWith("/") && relUrl.startsWith("/")) {
			url = baseURL.substring(0, baseURL.length() - 1) + relUrl;
		} else {
			url = baseURL + relUrl;
		}
		Context context = Context.getCurrent(false);
		if (context != null) {
			url += "?user=";
			String user = context.getUser();
			Locale locale = context.getLocale();
			if (user != null)
				url += user;
			url += "&locale=";
			if (locale != null)
				url += locale;
		}
		return url;
	}
	/**
	 * Retrieves a template from the provider application and renders it to the
	 * writer replacing the parameters with the given map. If "page" param is
	 * null, the whole page will be used as the template.<br />
	 * eg: The template "mytemplate" can be delimited in the provider page by
	 * comments "&lt;!--$begintemplate$mytemplate$--&gt;" and
	 * "&lt;!--$endtemplate$mytemplate$--&gt;".<br />
	 * Inside the template, the parameters can be defined by comments.<br />
	 * eg: parameter named "myparam" should be delimited by comments
	 * "&lt;!--$beginparam$myparam$--&gt;" and "&lt;!--$endparam$myparam$--&gt;"
	 */
	public void renderTemplate(String page, String name, Writer writer, Map<String, String> params) throws IOException {
		String content = getResourceAsString(page);
		if (content == null)
			content = "";
		if (name != null) {
			String beginString = "<!--$begintemplate$" + name + "$-->";
			String endString = "<!--$endtemplate$" + name + "$-->";
			int begin = content.indexOf(beginString);
			int end = content.indexOf(endString);
			if (begin == -1 || end == -1) {
				content = "";
				log.warn("Template not found: page=" + page + " template=" + name);
			} else {
				content = content.substring(begin + beginString.length(), end);
				log.debug("Serving template: page=" + page + " template=" + name);
			}
		}
		StringBuffer sb = new StringBuffer();
		Iterator<Map.Entry<String, String>> it = params.entrySet().iterator();
		if ("".equals(content)) {
			while (it.hasNext()) {
				Map.Entry<String, String> pairs = it.next();
				String value = pairs.getValue();
				sb = sb.append(value);
			}
		} else {
			sb.append(content);
			while (it.hasNext()) {
				Map.Entry<String, String> pairs = it.next();
				String key = pairs.getKey();
				String value = pairs.getValue();
				String beginString = "<!--$beginparam$" + key + "$-->";
				String endString = "<!--$endparam$" + key + "$-->";
				int begin = sb.toString().indexOf(beginString);
				int end = sb.toString().indexOf(endString);
				if (!(begin == -1 || end == -1)) {
					sb = sb.replace(begin + beginString.length(), end, value);
				}
			}
		}
		writer.write(sb.toString());
	}
	/**
	 * This method returns the content of an url. We check before in the cache
	 * if the content is here. If yes, we return the content of the cache. If
	 * not, we get it via an HTTP connection and put it in the cache.
	 * 
	 * @throws IOException
	 * @throws HttpException
	 */
	private String getResourceAsString(String relUrl) throws HttpException, IOException {
		StringOutput stringOutput = new StringOutput();
		try {
			renderResource(relUrl, stringOutput);
			return stringOutput.toString();
		} catch (ResourceNotFoundException e) {
			log.error("Page not found: " + relUrl);
			return "";
		}
	}
	/**
	 * Returns the base URL used to retrieve contents from the provider
	 * application.
	 * 
	 * @return the base URL as a String
	 */
	public String getBaseURL() {
		return baseURL;
	}
}
