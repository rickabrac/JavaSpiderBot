## Synopsis

Multi-threaded, single-domain web crawler in Java by Rick Tyler.

## Description

This crawler uses a breadth-first strategy to search an entire website, reachable subdomains
included. It honors the Crawl-delay setting in /robots.txt and prevents loading of disallowed pages. 
A separate thread is spawned for each concurrent subdomain concurrently being searched. The
asynchronous request rate is automatically throttled to avoid exceeding the website's ability to
respond while striving to optimize request rate.

## System Requirements 

JDK 11, Maven 3.6.3, Unix shell (/bin/sh)

## Installation

• Open terminal window and navigate to the project directory.

• Enter 'mvn compile assembly:single'

• Enter './crawl <url>' and watch it go. 

## Sample Output

	./crawl https://duckduckgo.com

	OK https://duckduckgo.com/robots.txt
	• 1.0/2.8 https://duckduckgo.com/about [1/1]
	ERROR 404 [https://duckduckgo.com/newsletter]
	/robots.txt DISALLOW [https://duckduckgo.com/search?foo=bar]

## Legend

	• Lines beginning with "•" indicate successfully crawled pages.

	  Format: • [<thread-request-rate>/rickbot-request-rate>] <url> [thread-pages-requested/rickbot-pages-requested]

	• ERROR indicates an HTTP/1.1 failure code. Note that failed requests are included in request-rate calculation.

	• The last line indicates a page disallowed by /robots.txt

## Limitations

• Switching Protocols (See HTTP/1.1 101) not supported.

• Pages that cannot be loaded due to transient failures should be retried. (ERROR java.net.SocketException: Network is down (Read failed))

• Robot meta tags not supported (https://developers.google.com/search/docs/advanced/robots/robots_meta_tag))

## Dependencies

• Uses crawlercommons.robots.* to enforce /robots.txt directives 
