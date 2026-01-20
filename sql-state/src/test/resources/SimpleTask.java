/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
import java.util.function.Function;

/**
 * @author hal.hildebrand
 *
 */
public class SimpleTask implements Function<long[], Long> {
    @Override
    public Long apply(long[] input) {
        if (input == null) {
            return null;
        }
        long total = 0;
        for (long number : input) {
            total += number;
        }
        return total;
    }
}
