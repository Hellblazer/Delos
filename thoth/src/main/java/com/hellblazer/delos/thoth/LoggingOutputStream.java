/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.thoth;

import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;

/**
 * @author hal.hildebrand
 */
public class LoggingOutputStream extends OutputStream {

    private final ByteArrayOutputStream baos = new ByteArrayOutputStream(1000);
    private final LogLevel              level;
    private final Logger                logger;

    public LoggingOutputStream(Logger logger, LogLevel level) {
        this.logger = logger;
        this.level = level;
    }

    @Override
    public void write(int b) {
        if (b == '\n') {
            String line = baos.toString();
            baos.reset();

            switch (level) {
            case TRACE:
                logger.trace(line);
                break;
            case DEBUG:
                logger.debug(line);
                break;
            case ERROR:
                logger.error(line);
                break;
            case INFO:
                logger.info(line);
                break;
            case WARN:
                logger.warn(line);
                break;
            }
        } else {
            baos.write(b);
        }
    }

    public enum LogLevel {
        DEBUG, ERROR, INFO, TRACE, WARN,
    }

}
