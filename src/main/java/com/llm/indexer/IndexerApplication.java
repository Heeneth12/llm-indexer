package com.llm.indexer;

import com.llm.indexer.cli.CliRoot;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;
import picocli.CommandLine;

import java.util.Set;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class IndexerApplication {

	private static final Set<String> CLI_SUBCOMMANDS = Set.of("build", "query");

	public static void main(String[] args) {
		if (args.length > 0 && CLI_SUBCOMMANDS.contains(args[0])) {
			// CLI mode (llm-index build / llm-index query): no web server, no Spring context.
			System.exit(new CommandLine(new CliRoot()).execute(args));
		} else {
			// Web mode: hosts the same indexing engine as a service.
			SpringApplication.run(IndexerApplication.class, args);
		}
	}

}
