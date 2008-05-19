package hudson.slaves;

import hudson.ExtensionPoint;
import hudson.model.*;
import hudson.util.DescriptorList;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Controls when to take {@link Computer} offline, bring it back online, or even to destroy it.
 * <p/>
 * <p/>
 * <b>EXPERIMENTAL: SIGNATURE MAY CHANGE IN FUTURE RELEASES</b>
 */
public abstract class RetentionStrategy<T extends Computer> implements Describable<RetentionStrategy<?>>, ExtensionPoint {

    /**
     * This method will be called periodically to allow this strategy to decide what to do with it's owning slave.
     *
     * @param c {@link Computer} for which this strategy is assigned. This object also exposes a bunch of properties
     *          that the callee can use to decide what action to take.
     * @return The number of minutes after which the strategy would like to be checked again. The strategy may be
     *         rechecked earlier or later that this!
     */
    public abstract long check(T c);

    /**
     * All registered {@link RetentionStrategy} implementations.
     */
    public static final DescriptorList<RetentionStrategy<?>> LIST = new DescriptorList<RetentionStrategy<?>>();

    /**
     * Dummy instance that doesn't do any attempt to retention.
     */
    public static final RetentionStrategy<Computer> NOOP = new RetentionStrategy<Computer>() {
        public long check(Computer c) {
            return 1;
        }

        public Descriptor<RetentionStrategy<?>> getDescriptor() {
            throw new UnsupportedOperationException();
        }
    };

    /**
     * Convenient singleton instance, sine this {@link RetentionStrategy} is stateless.
     */
    public static final Always INSTANCE = new Always();

    /**
     * {@link RetentionStrategy} that tries to keep the node online all the time.
     */
    public static class Always extends RetentionStrategy<SlaveComputer> {
        /**
         * Constructs a new Always.
         */
        @DataBoundConstructor
        public Always() {
        }

        /**
         * {@inheritDoc}
         */
        public long check(SlaveComputer c) {
            if (c.isOffline() && c.isLaunchSupported())
                c.tryReconnect();
            return 1;
        }

        /**
         * {@inheritDoc}
         */
        public DescriptorImpl getDescriptor() {
            return DESCRIPTOR;
        }

        public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

        private static class DescriptorImpl extends Descriptor<RetentionStrategy<?>> {
            /**
             * Constructs a new DescriptorImpl.
             */
            public DescriptorImpl() {
                super(Always.class);
            }

            /**
             * {@inheritDoc}
             */
            public String getDisplayName() {
                return "Keep this slave on-line as much as possible";
            }
        }

        static {
            LIST.add(DESCRIPTOR);
        }
    }

    /**
     * {@link hudson.slaves.RetentionStrategy} that tries to keep the node offline when not in use.
     */
    public static class Demand extends RetentionStrategy<SlaveComputer> {

        private static final Logger logger = Logger.getLogger(Demand.class.getName());

        /**
         * The delay (in minutes) for which the slave must be in demand before tring to launch it.
         */
        private final long inDemandDelay;

        /**
         * The delay (in minutes) for which the slave must be idle before taking it offline.
         */
        private final long idleDelay;

        @DataBoundConstructor
        public Demand(long inDemandDelay, long idleDelay) {
            this.inDemandDelay = inDemandDelay;
            this.idleDelay = idleDelay;
        }

        /**
         * Getter for property 'inDemandDelay'.
         *
         * @return Value for property 'inDemandDelay'.
         */
        public long getInDemandDelay() {
            return inDemandDelay;
        }

        /**
         * Getter for property 'idleDelay'.
         *
         * @return Value for property 'idleDelay'.
         */
        public long getIdleDelay() {
            return idleDelay;
        }

        public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

        /**
         * {@inheritDoc}
         */
        public synchronized long check(SlaveComputer c) {
            if (c.isOffline()) {
                final Queue queue = Hudson.getInstance().getQueue();
                final Queue.Item[] items = queue.getItems(); // TODO filter this array to this computer
                for (Queue.Item item : items) {
                    if ((System.currentTimeMillis() - item.timestamp.getTimeInMillis()) >
                            TimeUnit.MILLISECONDS.convert(inDemandDelay, TimeUnit.MINUTES)) {
                        // we've been in demand for long enough
                        logger.log(Level.INFO, "Trying to launch computer {0} as it has been in demand for too long", c.getNode().getNodeName());
                        if (c.isOffline() && c.isLaunchSupported())
                            c.tryReconnect();

                        break;
                    }
                }
            } else {
                if (c.isIdle()) {
                    if (System.currentTimeMillis() - c.getIdleStartMilliseconds() >
                            TimeUnit.MILLISECONDS.convert(inDemandDelay, TimeUnit.MINUTES)) {
                        // we've been idle for long enough
                        logger.log(Level.INFO, "Disconnecting computer {0} as it has been idle too long", c.getNode().getNodeName());
                        c.disconnect();
                    }
                }
            }
            return 1;
        }

        /**
         * {@inheritDoc}
         */
        public Descriptor<RetentionStrategy<?>> getDescriptor() {
            return DESCRIPTOR;
        }

        private static class DescriptorImpl extends Descriptor<RetentionStrategy<?>> {
            /**
             * Constructs a new DescriptorImpl.
             */
            public DescriptorImpl() {
                super(Demand.class);
            }

            /**
             * {@inheritDoc}
             */
            public String getDisplayName() {
                return "Take this slave on-line when in demand and off-line when idle";
            }
        }

        static {
            LIST.add(DESCRIPTOR);
        }
    }

    static {
        LIST.load(Demand.class);
    }
}
