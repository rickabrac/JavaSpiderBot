//
// Crawler.java - non-distributed single-domain web crawler
//
// Copyright 2021 Rick Tyler
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
// Usage: crawl <url>
//
// Output Example [ ./crawl https://duckduckgo.com ] :
//
//	OK https://duckduckgo.com/robots.txt
//	• https://duckduckgo.com/about
//		href=[https://duckduckgo.com/traffic]
//		href=[https://duckduckgo.com/app]
//		href=[https://duckduckgo.com/hiring]
//		href=[/hiring]
//		href=[/newsletter]
//		href=[https://duckduckgo.com/assets/email/DuckDuckGo-Privacy-Weekly_sample.png]
//		href=[https://spreadprivacy.com/delete-google-search-history]
//		href=[https://duckduckgo.com/privacy]
//		href=[https://duckduckgo.com/press]
//		href=[https://spreadprivacy.com]
//		href=[https://twitter.com/duckduckgo]
//		href=[https://reddit.com/r/duckduckgo]
//	ERROR 404 [https://duckduckgo.com/newsletter]
//	/robots.txt DISALLOW [https://duckduckgo.com/search?foo=bar]
//
// Legend:
//
//	Lines beginning with "•" indicate a successfully crawled page.
//
//	Indented lines with "href=[..."" indicate unique crawlable URLs within the preceeding page.
//
//	The ERROR line is an example of an HTTP file not found failure.
//
//	The last line indicates that crawling the page is disallowed by /robots.txt
//
//	Limitations: 
//
//  • HTTP/1.1 101 Switching Protocols is not supported.
//	• failed connections due to temporary network outages should be retried. 
//	• robot meta tags not supported (https://developers.google.com/search/docs/advanced/robots/robots_meta_tag)
//

package org.tyler.rickbot;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.regex.Pattern;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;
import java.net.URL;
import java.net.HttpURLConnection;
import javax.net.ssl.HttpsURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import crawlercommons.robots.*;    // relies on crawlercommons for /robots.txt enforcement

public class RickBot
{
	private boolean subdomainSwitching = true;           // allow subdomain switching
	private String domain;                               // common name of target site
	private Pattern tagRegex = null;                     // regex for valid xml tag 
	private HashMap< String, String > robotsTxtMap = null; // hash map of robots.txt content
	private ConcurrentHashSet< String > visited;         // concurrent hash set of visited pages
	private Long lastLoadMillis = 0L;                    // used to calculate crawlDelay
	private ExecutorService executor = null;             // used to run loadTarget() asynchronously
	final private int maxTagLength = 666;                // assumed max tag length used with regex matcher
	final private String _doctype = "<!DOCTYPE ";        // "<!DOCTYPE "
	final private String _robotsTxt = "/robots.txt";     // "robots.txt"
	final private String _protocolSeparator = "://";     // "://" 
	final private String _httpProtocol = "http://";      // "http://" 
	final private String _httpsProtocol = "https://";    // "https://" 
	final private String _httpColon = "http://";         // "http:" 
	final private String _httpsColon = "https://";       // "https:" 
	final private String _rickbot = "rickbot";           // robot's name 
	final private String [] _ignoreExtensions =
	{
		".dmg",
		".gif",
		".jpg",
		".jpeg",
		".pdf",
		".png",
		".json",
		".mov",
		".mp3",
		".m4a",
		".tar",
		".tgz",
		".xls",
		".xlsx",
		".zip"
	};
	Long started = 0L;

	RickBot ()
	{
		tagRegex = Pattern.compile( "<(\"[^\"]*\"|'[^']*'|[^'\">])*>" );    // matches valid xml tag
		robotsTxtMap = new HashMap<>();
		visited = new ConcurrentHashSet<>();
		executor = Executors.newFixedThreadPool( 10 );
	}

	private class ConcurrentHashSet< T >
	{
		private ConcurrentHashMap< T, String > map = null;

		ConcurrentHashSet () {
			map = new ConcurrentHashMap< T, String >();
		}

		void add ( T obj ) {
			map.put( obj, "" );
		}

		int size () {
			return( map.size() );
		}

		boolean contains ( T obj ) {
			return( map.get( obj ) != null );
		}
	}

	// thrown by HttpsRequest on http(s) redirect

	@SuppressWarnings( "serial" )
	private class RedirectException extends Exception
	{
		private String location;

		private RedirectException( String location ) {
			this.location = location;
		}

		private String getLocation () {
			return( location );
		}
	}

	// convenience class that parses a url and store its components
	
	public class HttpsTarget
	{
		private String protocol = null;             // "http://" or "https://"
		private String hostname = null;             // hostname componennt of url 
		private String port = null;                 // port specification if present or ""
		private String path = null;                 // "" or "/..."
		private boolean updateRobotsTxt = false;    // /robots.txt reload required

		HttpsTarget( String url )
		{
			if( !url.toLowerCase().startsWith( "http" ) )
				return;

			// handle escaped backslashes in protocol specifier
			final String _escapedSeparator = ":\\/\\/";

			// extract protocol
			int endProtocol = url.indexOf( _protocolSeparator ); 
			int offset = _protocolSeparator.length();
			if( endProtocol < 0 )	// must be escaped
			{
				endProtocol = url.indexOf( _escapedSeparator );
				offset = _escapedSeparator.length();
				protocol = url.substring( 0, endProtocol ) + _protocolSeparator;
			}
			else
				protocol = url.substring( 0, endProtocol + offset );

			// extract hostname and path
			int slashIndex = url.indexOf( "/", endProtocol + offset );
			path = "";
			if( slashIndex >= 0 )
			{
				hostname = url.substring( endProtocol + offset, slashIndex ).toLowerCase();
				if( slashIndex == url.length() - 1 )
					path = "";
				else
					path = url.substring( slashIndex, url.length() );
			}
			else
			{
				hostname = url.substring( endProtocol + offset, url.length() ).toLowerCase();
				path = "";
			}

			// remove trailing forward slash from hostname if present
			while( hostname.length() > 0 && hostname.charAt( hostname.length() - 1 ) == '\\' ) 
				hostname = hostname.substring( 0, hostname.length() - 1 );
			port = "";
			int colonIndex = hostname.indexOf( ":" );
			if( colonIndex >= 0 )
			{
				port = hostname.substring( colonIndex, hostname.length() );
				hostname = hostname.substring( 0, colonIndex );
			} 

			protocol = protocol.toLowerCase();
			hostname = hostname.toLowerCase();

			updateRobotsTxt = (robotsTxtMap.get( hostname ) == null ? true : false );
		}

		String getUrl () {  // url composed from stored components 
			return( protocol + hostname + port + path );
		}
	}

	// convenience class that loads pages asynchronously/concurrently

	private class HttpsLoader implements Runnable
	{
		private HttpsTarget target;
		private ArrayList< HttpsTarget > targets;
		
		HttpsLoader ( HttpsTarget target )
		{
			this.target = target;
			this.targets = new ArrayList<>();    // list of HttpsTargets to crawl
		}

		// convenience class using Http(s)URLConnection to load single page or /robots.txt

		private class HttpsRequest
		{
			private HttpURLConnection http = null;         // http connection object
			private HttpsURLConnection https = null;       // https connection object
			private StringBuilder stringBuilder = null;    // ouput string accumulator
			private boolean secure;                        // secure-mode on/off flag
			private boolean robotsTxtMode;                 // accept non-html text flag (required for /robots.txt)

			HttpsRequest ( String uri ) throws Exception
			{
				secure = true;
				if( !uri.startsWith( _httpsColon ) )
				{
					// not https://
					secure = false;
					if( !uri.startsWith( _httpColon ) )
						return; // ignore if not http:// (uncrawlable)
				}

				if( uri.endsWith( _robotsTxt ) ) 
					robotsTxtMode = true;	// allows receipt of text/plain content for /robots.txt

				if( uri.length() >= 1 )
				{
					// remove any trailing slashes
					while( uri.length() > 0 && uri.charAt( uri.length() - 1 ) == '/' )
						uri = uri.substring( 0, uri.length() - 1 );
				}

				URL url = new URL( uri );

				// initialize connection object
				if( secure )
				{	
					https = (HttpsURLConnection) url.openConnection();
					https.setRequestMethod( "GET" );
					https.setInstanceFollowRedirects( true );
					HttpsURLConnection.setFollowRedirects( true );
					https.setRequestProperty( "Content-Type", "application/x-www-form-urlencoded" );
					https.setRequestProperty( "User-Agent", _rickbot );
					https.setRequestProperty( "Accept", "text/html,text" );
				}
				else
				{	
					http = (HttpURLConnection) url.openConnection();
					http.setRequestMethod( "GET" );
					http.setInstanceFollowRedirects( true );
					HttpURLConnection.setFollowRedirects( true );
					http.setRequestProperty( "Content-Type", "application/x-www-form-urlencoded" );
					http.setRequestProperty( "User-Agent", _rickbot );
					http.setRequestProperty( "Accept", "text/html,text" );
				}
			}

			// loads single web page or /robots.txt

			private int execute () throws Exception
			{
				if( http == null && https == null )
					return( 666 );	// ignore non-http(s):// uri

				int responseCode = 0;
				if( secure )
				{
					https.setDoOutput( true );
					responseCode = https.getResponseCode();
				}
				else
				{
					http.setDoOutput( true );
					responseCode = http.getResponseCode();
				}
				if( responseCode == 301 || responseCode == 302 ) 
				{	// redirect
					String location = secure ? https.getHeaderField( "Location" ) : http.getHeaderField( "Location" );
					throw( new RedirectException( location ) );
				}

				if( responseCode != HttpURLConnection.HTTP_OK ) {
					return( responseCode );
				}

				String contentType = secure ? https.getContentType() : http.getContentType();
				if( !contentType.startsWith( "text" ) )
					return( 666 );	// ignore non-text

				stringBuilder = new StringBuilder();	// output accumulator
				BufferedReader br = new BufferedReader( new InputStreamReader(
					responseCode < 202 ? (secure ? https.getInputStream() : http.getInputStream()) :
					(secure ? https.getErrorStream() : http.getErrorStream()), StandardCharsets.UTF_8 ) );  
				String line = null;  
				while( (line = br.readLine() ) != null )
				{
					stringBuilder.append( line );
					if( robotsTxtMode )
						stringBuilder.append( "\n" );
				}
				br.close();  

				String page = stringBuilder.toString();

				if( !robotsTxtMode && page.indexOf( _doctype ) >= 0 )
				{
					// ignore non-html document
					int idx = page.indexOf( _doctype );
					if( idx < 0 )
						return( 666 );	// skip non-xml document
					idx += _doctype.length();	
					while( page.charAt( idx ) == ' ' )
						++idx;
					if( !page.substring( idx, idx + 4 ).toLowerCase().equals( "html" ) )
						return( 666 );	// skip non-html xml
				}

				return( responseCode );
			}

			// returns page content as a String

			private String getPage () throws Exception
			{
				if( stringBuilder == null )
					throw( new Exception( "HttpsRequest.execute() not called" ) );

				return( stringBuilder.toString() ); 
			}
		}

		// loads an http(s) page synchronously or asynchronously 

		private ArrayList< HttpsTarget > load () throws Exception
		{
			String protocol = target.protocol;
			String hostname = target.hostname;
			String port = target.port;
			String path = target.path;
			boolean updateRobotsTxt = target.updateRobotsTxt;

//			if( !subdomainSwitching && !hostname.equalsIgnoreCase( domain ) )
//				return( null );

//			// ignore non-html formats before http(s) get 
//			// assume file extension is accurate
//
//			for( int i = 0; i < _ignoreExtensions.length; i++ )
//			{
//				String ext = _ignoreExtensions[ i ];
//				if( path.endsWith( ext ) || path.indexOf( ext + "?" ) >= 0 )
//					return( null );
//			}

			String url = protocol + hostname + port + path;

			SimpleRobotRules robotRules = null;
			long crawlDelay = 1;
			boolean newRobotsTxt = false;

			if( updateRobotsTxt
				&& robotsTxtMap.get( hostname ) == null )
			{
				// load /robots.txt for new subdomain

				robotRules = null;
				HttpsRequest robotsHttp = null;

				try
				{
					robotsHttp = new HttpsRequest( protocol + hostname + _robotsTxt );

					int responseCode = robotsHttp.execute();
					if( responseCode == HttpURLConnection.HTTP_OK )
					{
						String robotsTxtStr = robotsHttp.getPage();
						robotsTxtMap.put( hostname, robotsTxtStr );
						newRobotsTxt = true;
					}
					if( responseCode == 101 )	// Switching Protocols
					{
						// give up if robots.txt cannot be read
						println( "Unable to crawl site: Switching Protocols (HTTP/1.1 101) is not supported." );
						System.exit( -1 );
					}
				}
				catch( RedirectException r )
				{
					// /robots.txt redirect
					
					protocol = protocol.indexOf( _httpsProtocol ) < 0 ? _httpProtocol : _httpsProtocol; 

					String location = r.getLocation();

					robotsHttp = new HttpsRequest( location ); 

					if( robotsHttp.execute() == HttpURLConnection.HTTP_OK )
					{
						String robotsTxtStr = robotsHttp.getPage();
						robotsTxtMap.put( hostname, robotsTxtStr );
						newRobotsTxt = true;
					}
				}
				catch( Exception e )
				{
					// misc failure 
					println( "  ERROR " + e.getMessage() );
					return( null );
				}
			}

			String robotsTxtStr = robotsTxtMap.get( hostname );

			if( robotsTxtStr != null )
			{
				// /robots.txt found for current subdomain

				SimpleRobotRulesParser robotsParser =  new SimpleRobotRulesParser();
				robotRules = robotsParser.parseContent( protocol + hostname,
					robotsTxtStr.getBytes( StandardCharsets.UTF_8 ), "text/plain; charset=UTF-8", _rickbot );

				// extract crawl-delay if present (crawlercommons.getCrawlDelay() appears broken)
				final String _crawlDelay = "crawl-delay:";
				int crawlDelayIndex = robotsTxtStr.toLowerCase().indexOf( _crawlDelay ); 
				if( crawlDelayIndex >= 0 ) 
				{
					crawlDelayIndex += _crawlDelay.length();
					while( crawlDelayIndex < robotsTxtStr.length() && robotsTxtStr.charAt( crawlDelayIndex ) == ' ')
						++crawlDelayIndex;
					if( crawlDelayIndex < robotsTxtStr.length() )
					{
						while( crawlDelayIndex < robotsTxtStr.length() && robotsTxtStr.charAt( crawlDelayIndex ) == ' ')
							++crawlDelayIndex;
					}
					int endCrawlDelay = crawlDelayIndex; 
					while( endCrawlDelay < robotsTxtStr.length()
						&& robotsTxtStr.charAt( endCrawlDelay ) != ' '
						&& robotsTxtStr.charAt( endCrawlDelay ) != '\r'
						&& robotsTxtStr.charAt( endCrawlDelay ) != '\n' )
					{
						++endCrawlDelay;
					}
					if( endCrawlDelay < robotsTxtStr.length() )
					{
						try
						{
							crawlDelay = Integer.parseInt( robotsTxtStr.substring( crawlDelayIndex, endCrawlDelay ) );
						}
						catch( Exception e )
						{
							println( "Integer.parse() failed (" + e.getMessage() + ")" );
							crawlDelay = 1;
						}
					}
				}
			}

			if( newRobotsTxt )
			{
				if( robotRules != null )
					println( "OK " + protocol + hostname + _robotsTxt + " crawl-delay=" + crawlDelay ); 
				else
					println( "NO " + protocol + hostname + _robotsTxt ); 
			}

			String uri = protocol + hostname + path; 

			if( robotRules != null )
			{
				// rules matcher for current subdomain ready
				if( !robotRules.isAllowed( protocol + hostname + path ) )
				{
					println( "/robots.txt DISALLOW [" + protocol + hostname + path + "]" );
					return( null );	// skip page blocked by /robots.txt
				}
			}

			HttpsRequest request = null;

			long beforeLoad = new Date().getTime();
			int responseCode = 666;    // made-up failure http(s) responseCode
			try {
				request  = new HttpsRequest( url );    // get page
				responseCode = request.execute();	
			}
			catch( RedirectException e )
			{
				// handle page redirect

				url = e.getLocation();

				if( url.indexOf( domain ) < 0 )
					return( null );	// ignore urls for different domains

				// update protocol, hostname and path, assumption: escaped syntax in redirect 
				int endProtocol = url.indexOf( "://" );
				if( endProtocol < 0 )
					throw( new Exception( "missing protocol specifier" ) );

				// update protocol in case changed 
				if( url.indexOf( _httpsProtocol ) >= 0 )
					protocol = _httpsProtocol;
				else
					protocol = _httpProtocol;
				int slashIndex = url.indexOf( "/", endProtocol + 3 );
				if( slashIndex >= 0 )
				{
					hostname = url.substring( endProtocol + 3, slashIndex ).toLowerCase();
					path = url.substring( slashIndex, url.length() );
					if( path.equals( "/" ) )
						path = "";
				}
				else
				{
					hostname = url.substring( endProtocol + 3, url.length() ).toLowerCase();
					path = ""; 
				}

//				// skip page if already visited
//				if( visited.contains( protocol + hostname + path ) )
//					return( null );

				// When subdomainSwitching is disabled, the crawler must allow initial an redirect to
				// succeed. For example, https://cnn.com immediately redirects to https://www.cnn.com,
				// which is technically a different subdomain. In this instance, the crawler allows it.
				// Subsequent subdomain redirects will fail when subdomainSwitching is disabled.

				if( !subdomainSwitching && visited.size() == 1 )
					domain = hostname;

				url = protocol + hostname + path;

				try
				{
					request =  new HttpsRequest( url );
					responseCode = request.execute();	
				}
				catch( Exception r )
				{
					println( "  REDIRECT FAILED" );
					return( null ); // redirect failed
				}
			}
			catch( Exception e )
			{
				// url is otherwise invalid
				println( "  ERROR: " + e.getMessage() );
				return( null );
			}

			if( responseCode != HttpURLConnection.HTTP_OK )
			{
				if( responseCode == 101 )
					println( "  ERROR 101 Switching Protocols not supported. " + responseCode + " [" + url + "]" );
				else
					println( "  ERROR " + responseCode + " [" + url + "]" );
				return( null );
			}

			int length = url.length();

			println( "• " + url );	// display page url

			ArrayList< String > protocols = new ArrayList<>();    // protocol list for pages to be visit 
			ArrayList< String > hostnames = new ArrayList<>();    // hostname ...
			ArrayList< String > ports = new ArrayList<>();        // port... 
			ArrayList< String > paths = new ArrayList<>();        // page...

			String html = request.getPage();

			// scan document for href tags 

			HashSet< String > urlSet = new HashSet<>();           // list of href urls displayed for this page so far 

			String maybeHref = null; 

			long afterLoad = new Date().getTime();
			long loadMillis = afterLoad - beforeLoad;
//	 		println( "LOAD: " + loadMillis + " ms @ " + 8 * 1000 * (html.length() / loadMillis) + " bps" );

			for( int tagStart = 0;
				(tagStart = html.indexOf( "<", tagStart )) >= 0 && tagStart < html.length();
				tagStart+=maybeHref.length() )
			{
				// use tagRegex to identify individual tags

				maybeHref = " "; 
				int tagEnd;
				for( tagEnd = tagStart + 1;
					tagEnd < html.length() && tagEnd < tagStart + maxTagLength;
					tagEnd += maybeHref.length() )
				{
					maybeHref = html.substring( tagStart, tagEnd );
					java.util.regex.Matcher matcher = tagRegex.matcher( maybeHref ); 
					if( matcher.matches() )
						break;
					maybeHref = " ";
				}
				if( maybeHref.equals( " " ) )
					continue;	// skip invalid tag 

				// if here, maybeHref contains a syntactically valid tag which may be an href
				// assume arbitrary spacing between tag components

				// scan for 'a' or 'A'
				int maybeHrefStart;
				for( maybeHrefStart = 1;
					maybeHrefStart < maybeHref.length() - 1 && maybeHref.charAt( maybeHrefStart ) == ' ';
					maybeHrefStart++ );
				if( maybeHrefStart == maybeHref.length() )
					continue;

				// scan for "href"
				if( maybeHref.charAt( maybeHrefStart ) != 'a' && maybeHref.charAt( maybeHrefStart ) != 'A' )
					continue; // not <a... tag 
				maybeHrefStart = maybeHref.toLowerCase().indexOf( " href", maybeHrefStart ); 
				if( maybeHrefStart < 0 ) 
					continue; // not href

				// scan for '=' 
				for( maybeHrefStart += 5;
					maybeHrefStart < maybeHref.length() - 1 && maybeHref.charAt( maybeHrefStart ) == ' ';
					maybeHrefStart++ );
				if( maybeHrefStart == maybeHref.length() )
					continue;
				if( maybeHref.charAt( maybeHrefStart ) != '=' )
					continue; // invalid tag

				// scan for opening single, double or escaped double quote
				for( ++maybeHrefStart;
					maybeHrefStart < maybeHref.length() - 1 && maybeHref.charAt( maybeHrefStart ) == ' ';
					maybeHrefStart++ );
				if( maybeHrefStart == maybeHref.length() )
					continue; // invalid tag

				char quoteDelimiter = maybeHref.charAt( maybeHrefStart );
				if( quoteDelimiter != '"' && quoteDelimiter != '\'' )
				{
					// check for ending escaped double quote
					if( maybeHrefStart == maybeHref.length() - 1 )
						continue; // invalid tag
					if( !maybeHref.substring( maybeHrefStart, maybeHrefStart + 2 ).equals( "\\\"" ) )
						continue; // invalid tag
					quoteDelimiter = '\"';
					maybeHrefStart += 2;
				}
				else
					++maybeHrefStart;
				int maybeHrefEnd;
				for( maybeHrefEnd = maybeHrefStart;
					maybeHrefEnd < maybeHref.length() - 1 && maybeHref.charAt( maybeHrefEnd ) != quoteDelimiter; 
					maybeHrefEnd++ )
				{
					// check for ending quote delimiter 
					if( quoteDelimiter == '\"' && maybeHrefEnd < maybeHref.length() - 1 &&
						maybeHref.charAt( maybeHrefEnd ) == '\\' && maybeHref.charAt( maybeHrefEnd + 1 ) == '\"' )
					{
						// an escaped double quote was detected
						break;
					}
				}
				if( maybeHrefEnd == maybeHref.length() )
					continue; // invalid tag 

				String href = maybeHref.substring( maybeHrefStart, maybeHrefEnd );

				if( href.length() == 0 )
					continue; // empty href

				if( href.indexOf( "mailto:" ) == 0
					|| href.indexOf( "tel:" ) == 0
					|| href.indexOf( "file:" ) == 0
					|| href.indexOf( "javascript:" ) == 0 )
				{
					continue; // skip non-http(s) href
				}

				// requests for non-text or html formats will be rejected due to "Accept:"
				// header setting but making http(s) requests that are guaranteed to fail
				// introduces unnecessary latency. 

				if( href.charAt( 0 ) == '#' )
					continue; // skip same-page link
					
				href = href.replace( "&#x2F;", "/" );	// fix mangled urls on yahoo.com

				// remove trailing slash if present
				if( href.length() > 0 && href.charAt( href.length() - 1 ) == '/' )
					href = href.substring( 0, href.length() - 1 );

				if( href.length() == 0 )
					continue; // skip blank href

				if( href.startsWith( "{" )
					|| href.startsWith( "+" ) )
				{
					continue; // not a real link
				}

				String _url;

				final String _escapedSeparator = ":\\/\\/";

				if( href.startsWith( "http" ) 
					&& (href.indexOf( _protocolSeparator ) >= 0 || href.indexOf( _escapedSeparator ) >= 0) )
				{
					// extract protocol, hostname and path
					int endProtocol = href.indexOf( _protocolSeparator ); 
					int offset = _protocolSeparator.length();
					String _protocol;
					if( endProtocol < 0 )	// must be escaped
					{
						endProtocol = href.indexOf( _escapedSeparator );
						offset = _escapedSeparator.length();
						_protocol = href.substring( 0, endProtocol ) + _protocolSeparator;
					}
					else
						_protocol = href.substring( 0, endProtocol + offset );
					String _hostname;
					String _path;
					int slashIndex = href.indexOf( "/", endProtocol + offset );
					if( slashIndex >= 0 )
					{
						_hostname = href.substring( endProtocol + offset, slashIndex ).toLowerCase();
						if( slashIndex == href.length() - 1 )
							_path = "";
						else
							_path = href.substring( slashIndex, href.length() );
					}
					else
					{
						_hostname = href.substring( endProtocol + offset, href.length() ).toLowerCase();
						_path = "";
					}

					// remove trailing forward slash from _hostname
					while( _hostname.length() > 0 && _hostname.charAt( _hostname.length() - 1 ) == '\\' ) 
						_hostname = _hostname.substring( 0, _hostname.length() - 1 );

					String _port = "";
					int colonIndex = _hostname.indexOf( ":" );
					if( colonIndex >= 0 )
					{
						_port = _hostname.substring( colonIndex, _hostname.length() );
						_hostname = _hostname.substring( 0, colonIndex );
					} 

					// ignore if different domain
					if( _hostname.indexOf( domain ) < 0 ) 
						continue;

					_url = _protocol + _hostname + _port + _path;

					// only display url once per page

					if( urlSet.contains( _url ) )
						continue; // already listed

					if( visited.contains( _url ) )
						continue; // already visited

					urlSet.add( _url );

					protocols.add( _protocol );
					hostnames.add( _hostname );
					ports.add( _port );
					paths.add( _path );
				}
				else
				{
					// href is relative path so use same protocol and hostname

					_url = protocol + hostname + href;

					if( href.indexOf( "//" ) == 0 )
						href = href.substring( 1, href.length() );

					protocols.add( protocol );
					hostnames.add( hostname );
					hostnames.add( port );
					paths.add( href );

					if( !href.startsWith( "/" ) )
					{
//						println( "\tINVALID href=[" + href + "]" );
						continue; // not a real link
					}

					if( urlSet.contains( _url ) )
						continue; // skip if already listed 

					if( visited.contains( _url ) )
						continue; // skip if previously visited

					urlSet.add( _url );
				}

				targets.add( new HttpsTarget( _url ) );

//				println( "\thref=[" + href + "]" );
			}

			return( targets );
		}

		// thread main()

		public void run ()
		{
			try
			{
				load();
			}
			catch( Exception e )
			{
				println( "HttpsLoader exception (" + e.getMessage() + "" );
				return;
			}
		}
	}

	// Runnable class crawls website in breadth-first order

	private class Crawler implements Runnable
	{
		private ArrayList< HttpsTarget > targets;
		private int crawlDelay = 1;                          // crawl delay 
		private boolean finished = false;

		Crawler( ArrayList< HttpsTarget > targets ) 
		{
			this.targets = targets;	
		}

		private ArrayList< HttpsTarget > crawl ()
		{
			// convenicent class encapsulates java.util.concurrent.Future to allow
			// polling for completion after asynchornously launching loader.
			// Asynchronous behavior is required to optimize the average page
			// crawling rate.

			final class HttpsLoaderAsync
			{
				HttpsLoaderAsync ( HttpsLoader loader, Future future )
				{
					this.loader = loader;
					this.future = future;
				}
				HttpsLoader loader;
				Future future;
			}

			ArrayList< HttpsTarget > pageTargets = new ArrayList< HttpsTarget >(); 
			ArrayList< HttpsTarget > newTargets = new ArrayList<>();    // urls to crawl appearing on this page 
			LinkedList< HttpsLoaderAsync > pending = new LinkedList< HttpsLoaderAsync >();
			
			for( Iterator it = targets.iterator(); it.hasNext(); )
			{
				HttpsTarget target = (HttpsTarget) it.next();

				String robotsTxtStr = robotsTxtMap.get( target.hostname );

				SimpleRobotRules robotRules = null;

				if( robotsTxtStr != null )
				{
					// /robots.txt found for current subdomain
					SimpleRobotRulesParser robotsParser =  new SimpleRobotRulesParser();
					robotRules = robotsParser.parseContent( target.protocol + target.hostname,
						robotsTxtStr.getBytes( StandardCharsets.UTF_8 ), "text/plain; charset=UTF-8", _rickbot );
					// rules matcher for current subdomain ready
					if( !robotRules.isAllowed( target.protocol + target.hostname + target.path ) )
					{
						println( "  /robots.txt DISALLOW " + target.protocol + target.hostname + target.path );
						continue;
					}
				}

				if( !subdomainSwitching && !target.hostname.equalsIgnoreCase( domain ) )
					continue;

				// ignore non-html formats before http(s) get - assume file extension is accurate
				for( int i = 0; i < _ignoreExtensions.length; i++ )
				{
					String ext = _ignoreExtensions[ i ];
					if( target.path.endsWith( ext ) || target.path.indexOf( ext + "?" ) >= 0 )
						continue;
				}
				
				String url = target.getUrl();

				if( visited.contains( url ) )
					continue;

				visited.add( url );

				newTargets.add( new HttpsTarget( url ) );

				try
				{
					long now = new Date().getTime();

					long runningSeconds = (now - started) / 1000L;
					float avgRequestRate = (float) (visited.size() + robotsTxtMap.size()) / ((float) (now - started) / 1000);
//					println( "RUNNING/LOADED: " + runningSeconds + "/" +  (robotsTxtMap.size() + visited.size()) );
//					println( String.format( "AVG REQUEST RATE: %.02f", avgRequestRate ) );

					if( lastLoadMillis > 0 && now - lastLoadMillis <= 1000 * crawlDelay )
					{
						long delay = 1000 * crawlDelay - (now - lastLoadMillis);
//						println( "SLEEP( " + delay + " )" );
						Thread.sleep( delay ); 
					}
					
					// load page asynchronously 

					HttpsLoader loader = new HttpsLoader( target );

					lastLoadMillis = new Long( new Date().getTime() );

					if( started == 0 )
						started = lastLoadMillis;

//					println( "*** SPAWNING LOADER *** [" + target.getUrl() + "]" );
					Future future = executor.submit( loader ); 

					pending.add( new HttpsLoaderAsync( loader, future ) );
				}
				catch( Exception e )
				{
					println( "crawl() Exception: " + e.getMessage() );
					continue;
				}
			}

			synchronized( pending )
			{
				while( pending.size() > 0 )
				{
					try
					{
						Thread.sleep( 10 );
						// add loaded targets to pageTargets 
						for( int i = 0; i < pending.size(); i++ )
						{
							HttpsLoaderAsync async = pending.get( i ); 
							if( async.future.get() == null )
							{
								// loader finished, add result to pageTargets
								pending.remove( async );
								if( async.loader.targets.size() == 0 )
									continue;
								HttpsLoader _loader = async.loader;
								ArrayList< HttpsTarget > _targets = _loader.targets;
								pageTargets.addAll( async.loader.targets ); 
							}
						}
					}
					catch( Exception e )
					{
						println( "Exception: " + e.getMessage() );
						System.exit( -1 );
					} 
					// ### HANDLE FAILED ###	
				}
			}
			if( pageTargets != null )
				newTargets.addAll( pageTargets );

			// add targets that failed to load to newTargets
			for( Iterator it = pending.iterator(); it.hasNext(); )
			{
				// ### UNTESTED ###
				HttpsTarget target = ((HttpsLoaderAsync) it.next()).loader.target;
				newTargets.add( target );
			}
			targets = newTargets;	

			return( targets );
		}

		private ArrayList< HttpsTarget > getTargets ()
		{
			return( targets );
		}

		public void run ()
		{
			targets = new Crawler( targets ).crawl(); 
			for( ;; )
			{
				if( targets.size() == 0 )
					break;

				targets = new Crawler( targets ).crawl();
			}
			finished = true;
		}
	}

	// called by main()

	public void crawlSite ( String url )  // instance method called by main()
	{
		try
		{
			// strip trailing slash(es)
			while( url.length() > 0 && url.charAt( url.length() - 1 ) == '/' )
				url = url.substring( 0, url.length() - 1 );
			int endProtocol = url.indexOf( "://" );
			if( endProtocol < 0 )
				throw( new Exception( "Invalid URL" ) );
			String protocol = url.substring( 0, endProtocol + 3 );
			int slashIndex = url.indexOf( "/", endProtocol + 3 );
			String path;
			String hostname;
			String port = "";
			if( slashIndex >= 0 )
			{
				hostname = url.substring( endProtocol + 3, slashIndex ).toLowerCase();
				path = url.substring( slashIndex, url.length() );
			}
			else 
			{
				hostname = url.substring( endProtocol + 3, url.length() );
				path = "";
			}
			// extract port spec
			int colonIndex = hostname.indexOf( ":" );
			if( colonIndex >= 0 )
			{
				port = hostname.substring( colonIndex, hostname.length() );
				hostname = hostname.substring( 0, colonIndex );
			} 

			// extract domain
			int dots = 0;
			for( int i = hostname.length() - 1; i >= 0; i-- )
			{
				if( hostname.charAt( i ) == '.' && ++dots == 2 )
				{
					domain = hostname.substring( i + 1, hostname.length() );
					break;
				}
			}
			
			if( domain == null )
				domain = hostname;

			ArrayList< HttpsTarget > targets = new ArrayList< HttpsTarget >();

			targets.add( new HttpsTarget( url ) );

			Crawler crawler = new Crawler( targets );

			Thread crawlerThread = new Thread( new Crawler( targets ) );

			crawlerThread.start();

			while( !crawler.finished )
			{
				if( lastLoadMillis > 0 && new Date().getTime() - lastLoadMillis > 30000 )
				{
					// crawler unresponsive so kill/restart
					crawlerThread.interrupt();
					lastLoadMillis = 0L;
					crawlerThread = new Thread( new Crawler( crawler.targets ) );
				}
				Thread.sleep( 1000 );
			}

			executor.shutdown();

			println( "" + (robotsTxtMap.size() + visited.size()) + " pages crawled." );
		}
		catch( Exception e )
		{
			println( e.getMessage() );
			System.exit( -1 );
		}
	}

	static public void main ( String [] args )
	{
		if( args.length != 1 )
		{
			println( "Usage: crawl <url>" );
			System.exit( -1 );
		}
		RickBot rickbot = new RickBot();
		rickbot.crawlSite( args[ 0 ] );
	}

	private static void println ( String s ) {  // convenience method 
		System.out.println( s );
	}
}
