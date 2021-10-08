//
// RickBot.java - multi-threaded, single-domain web crawler by Rick Tyler 
//
// Copyright 2021 Rick Tyler
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License You may obtain a copy of the License at
//
// https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
// Limitations: 
//
//  • HTTP/1.1 101 Switching Protocols not supported.
//	• Failed connections due to temporary outages should be retried.
//	• Robot meta tags not supported (https://developers.google.com/search/docs/advanced/robots/robots_meta_tag)
//

package org.tyler.rickbot;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;
import java.net.URL;
import java.net.HttpURLConnection;
import javax.net.ssl.HttpsURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import crawlercommons.robots.*;    // relies on crawlercommons for /robots.txt enforcement

public class RickBot
{
	ConcurrentHashMap< String, ConcurrentHashSet< String > > subdomainRequested; // previously requested pages for each subdomain 
	public static RickBot rickbot = null;                       // singleton app instance 
	private final String _rickbot = "rickbot";                  // robot name 
	private ConcurrentLinkedQueue< SiteCrawlerAsync > crawlers; // active crawler instances 
	private HashMap< String, String > robotsTxtMap;             // robots.txt content indexed by subdomain
	private ConcurrentHashSet< String > beenThere;              // set of pages previously visited by any instance 
	private Pattern xmlTagRegex;                                // regex for a valid xml tag (see PageCrawler)
	ExecutorService executor;                                   // used to manage concurrent site crawlers
	private long botStarted;                                    // rickbot launch timestamp
	private final static String _httpProtocol = "http://";      // "http://" 
	private final static String _httpsProtocol = "https://";    // "https://" 
	private final int maxTagLength = 666;                       // assumed max html tag length (never exceeded in testing)
	private final String _doctype = "<!DOCTYPE ";               // "<!DOCTYPE "
	private final String _robotsTxt = "/robots.txt";            // "robots.txt"
	private final String _protocolSeparator = "://";            // "://" 
	private final String _httpColon = "http://";                // "http:" 
	private final String _httpsColon = "https://";              // "https:" 
	private final String [] _ignoreExtensions =                 // file extensions to ignore
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
		".zip",
		".tar",
		".tgz",
		".rar"	
	};

	// Rickbot singleton class 

	RickBot ()
	{
		crawlers = new ConcurrentLinkedQueue<>(); 
		xmlTagRegex = Pattern.compile( "<(\"[^\"]*\"|'[^']*'|[^'\">])*>" );    // matches valid xml tag
		robotsTxtMap = new HashMap<>();
		executor = Executors.newFixedThreadPool( 100 );    // assume 50 subdomains maximum
		beenThere = new ConcurrentHashSet<>();
		subdomainRequested = new ConcurrentHashMap<>();
	}

	// ConcurrentHashSet<> is a concurrent HashSet<> implemented on top of ConcurrentHashMap<>

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

		T remove ( T obj ) {
			return( (T) map.remove( obj ) );
		}
	}

	// Used to manage asynchronous subdomain crawler threads 

	private class SiteCrawlerAsync
	{
		SiteCrawler crawler;
		Future future;

		SiteCrawlerAsync ( SiteCrawler crawler, Future future )
		{
			this.crawler = crawler;
			this.future = future;
		}
	}

	// Searches a single subdomain asynchronously

	private class SiteCrawler implements Runnable
	{
		ConcurrentHashSet< String > requested;          // concurrent hash set of visited pages
		String url = null;                              // root url passed to crawler 
		boolean subdomainSwitching = true;              // allow subdomain switching
		String domain;                                  // common name of target site
		Long lastLoadMillis = 0L;                       // used to throttle request rate
		long crawlDelay = 1L;                           // crawl-delay for this crawler
		Long started;                                   // start time
		int threadIdx;                                  // thread index used for output tracing
		SiteCrawler crawler;                            // self instance
		ArrayList< HttpsTarget > targets;               // current list of page targets
		ArrayList< HttpsTarget > addLinks;              // link(s) to add to subdomain crawler targets

		SiteCrawler( String url )
		{
			this.url = url;
			domain = new HttpsTarget( url ).hostname;
			requested = subdomainRequested.get( domain );
			// get requested page map if exists
			if( requested == null )
			{
				requested = new ConcurrentHashSet<>();
				subdomainRequested.put( domain, requested );
			}
			threadIdx = crawlers.size() + 1;
			started = new Date().getTime();
			if( botStarted == 0 )
				botStarted = started; 
			crawler = this;
			addLinks = new ArrayList<>();
		}
		
		// thrown by HttpsRequest on http(s) redirect

		@SuppressWarnings( "serial" )
		private class RedirectException extends Exception
		{
			String location;

			RedirectException( String location ) {
				this.location = location;
			}

			String getLocation () {
				return( location );
			}
		}

		// Parses a URL and stores the components (protocol, hostname, port, path)
		// e.g. protocol="https://", hostname="support.example.com", port=":443", path="/welcome.html")
		// would correspond to "https://support.example.com:443/welcome.html"
		
		private class HttpsTarget
		{
			String protocol = null;             // "http://" or "https://"
			String hostname = null;             // hostname component of url 
			String port = null;                 // port specification if present or ""
			String path = null;                 // "" or "/..."
			boolean updateRobotsTxt = false;    // /robots.txt reload required

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

		// Enforces max crawl-rate when connection to website is not bandwidth-saturated

		private void delayCrawl ( String url ) throws Exception
		{
			long delay = 0;
			synchronized( lastLoadMillis )
			{
				long now = new Date().getTime();
				if( lastLoadMillis > 0 && now - lastLoadMillis <= 1000 * crawlDelay )
					delay = 1000 * crawlDelay - (now - lastLoadMillis);
			}
			if( delay > 0 ) 
			{
//				println( "sleep( " + delay + " ) [" + url + "]" );
				Thread.sleep( delay ); 
			}
		} 

		// Loads a single web page asynchronously

		private class PageLoader implements Runnable
		{
			HttpsTarget target;
			ArrayList< HttpsTarget > targets;
			
			PageLoader ( HttpsTarget target )
			{
				this.target = target;
				this.targets = new ArrayList<>();    // list of HttpsTargets to crawl
			}

			// Loads a single web page or /robots.txt

			private class HttpsRequest
			{
				HttpURLConnection http = null;         // http connection object
				HttpsURLConnection https = null;       // https connection object
				StringBuilder stringBuilder = null;    // ouput string accumulator
				boolean secure;                        // secure-mode on/off flag
				boolean robotsTxtMode;                 // accept non-html text flag (required for /robots.txt)

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

				// Loads a single web page or /robots.txt

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

			// Loads a single web page and returns all crawlable urls

			private ArrayList< HttpsTarget > loadPage () throws Exception
			{
				String protocol = target.protocol;
				String hostname = target.hostname;
				String port = target.port;
				String path = target.path;
				boolean updateRobotsTxt = target.updateRobotsTxt;
				String pageReport;
				ArrayList< String > hrefList = new ArrayList<>();

				String url = protocol + hostname + port + path;

				SimpleRobotRules robotRules = null;
				boolean newRobotsTxt = false;

				if( updateRobotsTxt
					&& robotsTxtMap.get( hostname ) == null )
				{
					if( !hostname.equals( domain ) )
					{
						// url is for a different subdomain
						synchronized( crawlers )
						{
							if( beenThere.contains( url ) ) 
								return( null );

							// start new crawler for new subdomain
							SiteCrawler crawler = rickbot.new SiteCrawler( url ); 
							Future future = executor.submit( crawler ); 
							int crawlerIndex = -1;
							synchronized( crawlers )
							{
								Iterator it = crawlers.iterator();
								while( it.hasNext() )
								{
									++crawlerIndex;
									if( ((SiteCrawlerAsync) it.next()).crawler == crawler )
										break;
								}
							}
							crawlers.add( new SiteCrawlerAsync( crawler, future ) );
						}
//						println( "NEW crawler[ " + (crawlerIndex + 1) + " ] " + hostname );
						return( null );
					}

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
						
						delayCrawl( protocol + hostname + port );

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
				
				if( !hostname.equals( domain ) && !beenThere.contains( url ) )
				{
					// add url to subdomain crawler's queue
					HttpsTarget subdomainTarget = new HttpsTarget( url );
					synchronized( crawlers ) 
					{
						crawler = null;
						// scan list of crawlers to get subdomain crawler
						Iterator it = crawlers.iterator();
						while( it.hasNext() )
						{
							SiteCrawlerAsync async = (SiteCrawlerAsync) it.next();
							if( target.hostname.equals( subdomainTarget.hostname ) )
							{
								crawler = async.crawler;
								break;
							}
						}
						if( crawler == null )
							throw( new Exception( "crawler == null" ) );
						crawler.addLinks.add( target );
					}
//					println( "  ADDED DEEP LINK [" + url + "] TO SUBDOMAIN CRAWLER" );
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
					{
						println( "OK " + protocol + hostname + _robotsTxt + " crawl-delay=" + crawlDelay ); 
						Thread.sleep( 1000 * crawlDelay );
					}
					else
						println( "NO " + protocol + hostname + _robotsTxt ); 
				}

				String uri = protocol + hostname + path; 

				if( robotRules != null )
				{
					// rules matcher for current subdomain ready
					if( !robotRules.isAllowed( protocol + hostname + path ) )
					{
						println( "  DISALLOW " + protocol + hostname + path  );
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
					// page redirect

					delayCrawl( protocol + hostname + port );

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

					url = protocol + hostname + path;

					try
					{
						request =  new HttpsRequest( url );
						responseCode = request.execute();
					}
					catch( Exception r )
					{
						println( "  REDIRECT FAILED TO " + url );
						return( null ); // redirect failed
					}
				}
				catch( Exception e )
				{
					// url is otherwise invalid
					println( "  ERROR " + e.getMessage() + " [" + target.getUrl() + "]" );
					return( null );
				}

				if( responseCode != HttpURLConnection.HTTP_OK )
				{
					if( responseCode == 101 )
						println( "  ERROR 101 Switching Protocols not supported. " + responseCode + " [" + url + "]" );
					else
					{
						println( "  ERROR " + responseCode + " [" + url + "]" );
						requested.add( url );
						beenThere.add( url );
					}
					return( null );
				}

				long botVisited = 0;
				synchronized( beenThere )
				{
					if( beenThere.contains( url ) )
						return( null ); // already visited
					beenThere.add( url );
				}

				long crawlerRunningSeconds = (new Date().getTime() - started) / 1000L + 1;
				long botRunningSeconds = (new Date().getTime() - botStarted) / 1000L + 1;
				float avgRequestRate = (float) (requested.size() + robotsTxtMap.size()) / crawlerRunningSeconds; 
				float botRequestRate = (float) (beenThere.size() + robotsTxtMap.size()) / botRunningSeconds; 
				long now = new Date().getTime();
				int crawlerIndex = -1;
				synchronized( crawlers )
				{
					Iterator it = crawlers.iterator();
					while( it.hasNext() )
					{
						++crawlerIndex;
						if( ((SiteCrawlerAsync) it.next()).crawler == crawler )
							break;
					}
				}

				pageReport = String.format( "• %.01f/%.01f Crawler[%d/%d] %s [%d/%d]",
					avgRequestRate, botRequestRate, crawlerIndex + 1, crawlers.size(), url,
					requested.size() + 1, beenThere.size() + robotsTxtMap.size() );

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

				for( int tagStart = 0;
					(tagStart = html.indexOf( "<", tagStart )) >= 0 && tagStart < html.length();
					tagStart+=maybeHref.length() )
				{
					// use xmlTagRegex to identify individual tags

					maybeHref = " "; 
					int tagEnd;
					for( tagEnd = tagStart + 1;
						tagEnd < html.length() && tagEnd < tagStart + maxTagLength;
						tagEnd += maybeHref.length() )
					{
						maybeHref = html.substring( tagStart, tagEnd );
						java.util.regex.Matcher matcher = xmlTagRegex.matcher( maybeHref ); 
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
						int questionMarkIndex = href.indexOf( "?", endProtocol + offset );
						if( slashIndex >= 0 )
						{
							_hostname = href.substring( endProtocol + offset, slashIndex ).toLowerCase();
							if( slashIndex == href.length() - 1 )
								_path = "";
							else
								_path = href.substring( slashIndex, href.length() );
						}
						else if( questionMarkIndex >= 0 )
						{
							_hostname = href.substring( endProtocol + offset, questionMarkIndex ).toLowerCase();
							_path = href.substring( questionMarkIndex, href.length() );
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

						urlSet.add( _url );

						if( requested.contains( _url ) )
							continue; // already visited

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
							href = protocol + href.substring( 2, href.length() );

						if( !href.startsWith( "/" ) )
							continue; // not a valid href

						if( urlSet.contains( _url ) )
							continue; // skip if already listed 

						urlSet.add( _url );

						if( requested.contains( _url ) )
							continue; // skip if previously visited

						protocols.add( protocol );
						hostnames.add( hostname );
						ports.add( port );
						paths.add( href );
					}

					targets.add( new HttpsTarget( _url ) );
				}

				synchronized( rickbot )
				{
					println( pageReport );
//					for( int i = 0; i < hrefList.size(); i++ )	// print each unique crawlable href on page
//						println( hrefList.get( i ) );
				}

				return( targets );
			}

			// Runnable interface entry point 

			public void run ()
			{
				try {
					loadPage();
				}
				catch( Exception e )
				{
					println( "HttpsLoader exception (" + e.getMessage() + "" );
					return;
				}
			}
		}

		// Crawls one web page and returns list of crawlable urls. 
		// Used to implement depth-first search by SiteCrawler run().

		private class PageCrawler implements Runnable
		{
			ArrayList< HttpsTarget > targets;
			int crawlDelay = 1;    // default/min crawl delay

			PageCrawler( ArrayList< HttpsTarget > targets ) {
				this.targets = targets;	
			}

			ArrayList< HttpsTarget > crawlPage ()
			{
				// convenience class encapsulates java.util.concurrent.Future to allow
				// polling for completion after asynchornously launching loader.
				// Asynchronous behavior is required to optimize the average page
				// crawling rate.

				final class HttpsLoaderAsync
				{
					HttpsLoaderAsync ( PageLoader loader, Future future )
					{
						this.loader = loader;
						this.future = future;
					}
					PageLoader loader;
					Future future;
				}

				ArrayList< HttpsTarget > pageTargets = new ArrayList< HttpsTarget >(); 
				ArrayList< HttpsTarget > newTargets = new ArrayList<>();    // urls to crawl appearing on this page 
				HttpsLoaderAsync pending = null; 
				
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
							println( "  DISALLOW " + target.protocol + target.hostname + target.path );
							continue;
						}
					}

					if( !subdomainSwitching && !target.hostname.equalsIgnoreCase( domain ) )
						continue;

					// ignore non-html formats before http(s) get - assume file extension is accurate
					for( int i = 0; i < _ignoreExtensions.length; i++ )
					{
						String ext = _ignoreExtensions[ i ];
						if( target.path.toLowerCase().indexOf( ext ) > 0 ) // || target.path.indexOf( ext + "?" ) >= 0 )
							continue;
					}
					
					String url = target.getUrl();

					synchronized( requested )
					{
						if( requested.contains( url ) )
							continue;
						requested.add( url );
					}

					newTargets.add( new HttpsTarget( url ) );

					for(;;)
					{
						try
						{
							if( pending == null )
								delayCrawl( target.protocol + target.hostname + target.port );
							else
							{
								int throttling = (int) (0.02 * (crawlers.size() * crawlers.size()));
								// This heuristic throttles the request rate per subdomain agressively as
								// the number of concurrent crawlers increases to avoid saturating cpu while
								// maintaining max sustainable request rate, subject to subdomain bandwidth
								// constraints. 

								if( throttling > 0 )
								{
//									println( "sleep( " + (1000 * delay ) + " )" );
									Thread.sleep( 1000 * throttling );
								}
							}

							lastLoadMillis = new Long( new Date().getTime() );

							// load next page asynchronously 

							PageLoader loader = new PageLoader( target );

							if( pending != null )
								Thread.sleep( 100 );
							else
							{
								Future future = executor.submit( loader ); 
								pending = new HttpsLoaderAsync( loader, future );
							}
						}
						catch( Exception e )
						{
							println( "crawlPage() Exception: " + e.getMessage() );
							continue;
						}
						try
						{
							if( pending != null && (pending.future.isDone() || pending.future.isCancelled()) )
							{
								// loader finished, add result to pageTargets
								PageLoader _loader = pending.loader;
								ArrayList< HttpsTarget > _targets = _loader.targets;
								pageTargets.addAll( pending.loader.targets ); 
								pending = null;
							}
						}
						catch( Exception e )
						{
							println( "Exception: " + e.getMessage() );
							System.exit( -1 );
						} 

						if( pending == null )
							break;
					}
				}
				while( pending != null ) 
				{
					try
					{
						Thread.sleep( 5 );
						// add loaded targets to pageTargets 

						if( pending.future.isDone() || pending.future.isCancelled() )
						{
							// background loader finished, add result to pageTargets
							PageLoader _loader = pending.loader;
							ArrayList< HttpsTarget > _targets = _loader.targets;
							pageTargets.addAll( pending.loader.targets ); 
							pending = null;
							continue;
						}

						if( pending != null && new Date().getTime() - lastLoadMillis > 10000 * crawlDelay )
						{
							// something must be wedged
							targets.clear();
							if( pending.future.cancel( true ) )
								println( "WEDGED LOADER CANCELLED" );
							targets.add( pending.loader.target ); 
							println( "  RECRAWLING " + targets.size() + " PENDING TARGETS!" );
							return( crawlPage() );
						}
					}
					catch( InterruptedException e )
					{
						println( "InterruptedException: " + e.getMessage() );
						System.exit( -1 );
					}
					catch( Exception e )
					{
						println( "Exception: " + e.getMessage() );
						System.exit( -1 );
					} 
				}

				if( pageTargets != null )
					newTargets.addAll( pageTargets );
				return( newTargets );
			}

			ArrayList< HttpsTarget > getTargets () {
				return( targets );
			}

			public void run ()
			{
				targets = new PageCrawler( targets ).crawlPage(); 
				for( ;; )
				{
					if( targets == null || targets.isEmpty() ) // targets.size() == 0 )
						break;
					targets = new PageCrawler( targets ).crawlPage();
				}
			}
		}

		public void run ()
		{
			try
			{
				targets = new ArrayList<>();
				targets.add( new HttpsTarget( url ) );
				PageCrawler pageCrawler = new PageCrawler( targets );
				while( true )
				{
					targets = pageCrawler.crawlPage();
					if( targets == null || targets.isEmpty() )
						break;
					synchronized( crawlers )
					{
						if( addLinks.size() > 0 )
							targets.addAll( addLinks );
					}
					pageCrawler = new PageCrawler( targets );
				}
//				while( crawlers.size() > 0 )
//					Thread.sleep( 1000 );	// important!
			}  
			catch( Exception e )
			{
				println( "PageLoader Exception (" + e.getMessage() + ")" );
				return;
			}
		}
	}

	public void run ( String url )
	{
		try
		{
			SiteCrawler crawler = rickbot.new SiteCrawler( url ); 

			Future future = executor.submit( crawler ); 

			crawlers.add( new SiteCrawlerAsync( crawler, future ) );

			// wait for crawler(s) to finish

			while( crawlers.size() > 0 )
				Thread.sleep( 10000 );

			executor.shutdown();
		}
		catch( Exception e )
		{
			println( "SiteCrawler.run() Exception: " + e.getMessage() );
			System.exit( -1 );
		}
	}

	static public void main ( String [] args )
	{
		rickbot = new RickBot();
		if( args.length != 1 ||
			(!args[ 0 ].startsWith( _httpProtocol ) && !args[ 0 ].startsWith( _httpsProtocol )) )
		{
			println( "Usage: crawl <url>" );
			System.exit( -1 );
		}
		rickbot.run( args[ 0 ] );
	}

	private static void println ( String s ) {  // convenience method 
		System.out.println( s );
	}
}
