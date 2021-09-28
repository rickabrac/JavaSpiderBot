## Synopsis

Single domain web crawler in Java by Rick Tyler.

## Description

This crawler uses a breadth-first strategy to search an entire website, subdomains included. 

## System Requirements 

JDK 11, Maven 3.6.3

## Installation

• Open terminal window and navigate to rickbot directory

• Enter 'mvn compile assembly:single' at command prompt

• Type './crawl <url>' to crawl a website. 

## Limitations

• Switching Protocols (if requested via HTTP/1.1 101) is not supported.

• Transient connection failures are not retried. 

• Robot meta tags not supported (https://developers.google.com/search/docs/advanced/robots/robots_meta_tag))

## Dependencies

• Relies on crawlercommons.robots.* for /robots.txt enforcement
