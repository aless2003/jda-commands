package com.github.kaktushose.jda.commands.dispatching;

import com.github.kaktushose.jda.commands.JDACommands;
import com.github.kaktushose.jda.commands.dependency.DependencyInjector;
import com.github.kaktushose.jda.commands.dispatching.adapter.TypeAdapterRegistry;
import com.github.kaktushose.jda.commands.dispatching.filter.Filter;
import com.github.kaktushose.jda.commands.dispatching.filter.FilterRegistry;
import com.github.kaktushose.jda.commands.dispatching.filter.FilterRegistry.FilterPosition;
import com.github.kaktushose.jda.commands.dispatching.parser.ParserSupervisor;
import com.github.kaktushose.jda.commands.dispatching.router.Router;
import com.github.kaktushose.jda.commands.dispatching.sender.MessageSender;
import com.github.kaktushose.jda.commands.dispatching.validation.ValidatorRegistry;
import com.github.kaktushose.jda.commands.embeds.help.HelpMessageFactory;
import com.github.kaktushose.jda.commands.reflect.CommandDefinition;
import com.github.kaktushose.jda.commands.reflect.CommandRegistry;
import com.github.kaktushose.jda.commands.reflect.ImplementationRegistry;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.sharding.ShardManager;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;

/**
 * Dispatches commands by taking a {@link CommandContext} and passing it through the execution chain.
 *
 * @author Kaktushose
 * @version 2.0.0
 * @since 2.0.0
 */
public class CommandDispatcher {

    private static final Logger log = LoggerFactory.getLogger(CommandDispatcher.class);
    private static boolean isActive;
    private final Object jda;
    private final boolean isShardManager;
    private final ImplementationRegistry implementationRegistry;
    private final ParserSupervisor parserSupervisor;
    private final FilterRegistry filterRegistry;
    private final TypeAdapterRegistry adapterRegistry;
    private final ValidatorRegistry validatorRegistry;
    private final CommandRegistry commandRegistry;
    private final DependencyInjector dependencyInjector;
    private final JDACommands jdaCommands;

    /**
     * Constructs a new CommandDispatcher.
     *
     * @param jda            the corresponding {@link JDA} instance
     * @param isShardManager whether the jda instance is a shard manager
     * @param jdaCommands    the corresponding {@link JDACommands} instance
     * @param packages       optional packages to exclusively scan
     * @param clazz          a class of the classpath to scan
     * @throws IllegalStateException if an instance of this class is already active.
     */
    public CommandDispatcher(@NotNull Object jda,
                             boolean isShardManager,
                             @NotNull JDACommands jdaCommands,
                             @NotNull Class<?> clazz,
                             @NotNull String... packages) {
        this.jda = jda;
        this.isShardManager = isShardManager;
        this.jdaCommands = jdaCommands;

        if (isActive) {
            throw new IllegalStateException("An instance of the command framework is already running!");
        }

        dependencyInjector = new DependencyInjector();
        dependencyInjector.index(clazz, packages);

        filterRegistry = new FilterRegistry();
        adapterRegistry = new TypeAdapterRegistry();
        validatorRegistry = new ValidatorRegistry();

        implementationRegistry = new ImplementationRegistry(dependencyInjector, filterRegistry, adapterRegistry, validatorRegistry);
        implementationRegistry.index(clazz, packages);

        parserSupervisor = new ParserSupervisor(this);
        if (isShardManager) {
            ((ShardManager) jda).addEventListener(parserSupervisor);
        } else {
            ((JDA) jda).addEventListener(parserSupervisor);
        }

        commandRegistry = new CommandRegistry(adapterRegistry, validatorRegistry, dependencyInjector);
        commandRegistry.index(clazz, packages);

        dependencyInjector.inject();
        isActive = true;
    }

    /**
     * Whether this CommandDispatcher is active.
     *
     * @return {@code true} if the CommandDispatcher is active
     */
    public static boolean isActive() {
        return isActive;
    }

    /**
     * Shuts down this CommandDispatcher instance, making it unable to receive any events from Discord.
     */
    public void shutdown() {
        if (isShardManager) {
            ((ShardManager) jda).removeEventListener(this);
        } else {
            ((JDA) jda).removeEventListener(this);
        }
        isActive = false;
    }

    /**
     * Dispatches a {@link CommandContext}. This will route the command, apply all filters and parse the arguments.
     * Finally, the command will be executed.
     *
     * @param context the {@link CommandContext} to dispatch.
     */
    public void onEvent(@NotNull CommandContext context) {
        log.debug("Applying filters in phase BEFORE_ROUTING...");
        for (Filter filter : filterRegistry.getAll(FilterPosition.BEFORE_ROUTING)) {
            filter.apply(context);
            if (checkCancelled(context)) {
                return;
            }
        }

        HelpMessageFactory helpMessageFactory = implementationRegistry.getHelpMessageFactory();
        Router router = implementationRegistry.getRouter();
        MessageSender sender = implementationRegistry.getMessageSender();

        router.findCommands(context, commandRegistry.getCommands());

        if (context.isCancelled() && context.isHelpEvent()) {
            log.debug("Sending generic help");
            sender.sendGenericHelpMessage(context, helpMessageFactory.getGenericHelp(commandRegistry.getControllers(), context));
            return;
        }

        if (checkCancelled(context)) {
            log.debug("No matching command found!");
            return;
        }

        CommandDefinition command = context.getCommand();
        log.debug("Input matches command: {}", command);

        if (context.isHelpEvent()) {
            log.debug("Sending specific help");
            sender.sendSpecificHelpMessage(context, helpMessageFactory.getSpecificHelp(context));
            return;
        }

        log.debug("Applying filters in phase BEFORE_ADAPTING...");
        for (Filter filter : filterRegistry.getAll(FilterPosition.BEFORE_ADAPTING)) {
            filter.apply(context);
            if (checkCancelled(context)) {
                return;
            }
        }

        adapterRegistry.adapt(context);
        if (checkCancelled(context)) {
            return;
        }

        log.debug("Applying filters in phase BEFORE_EXECUTION...");
        for (Filter filter : filterRegistry.getAll(FilterPosition.BEFORE_EXECUTION)) {
            filter.apply(context);
            if (checkCancelled(context)) {
                return;
            }
        }

        if (checkCancelled(context)) {
            return;
        }

        log.info("Executing command {} for user {}", command.getMethod().getName(), context.getEvent().getAuthor());
        try {
            log.debug("Invoking method with following arguments: {}", context.getArguments());
            command.getMethod().invoke(command.getInstance(), context.getArguments().toArray());
        } catch (Exception e) {
            log.error("Command execution failed!", new InvocationTargetException(e));
        }
    }

    private boolean checkCancelled(CommandContext context) {
        if (context.isCancelled()) {
            implementationRegistry.getMessageSender().sendErrorMessage(context, context.getErrorMessage());
            return true;
        }
        return false;
    }

    /**
     * Gets the {@link ImplementationRegistry}.
     *
     * @return the {@link ImplementationRegistry}
     */
    public ImplementationRegistry getImplementationRegistry() {
        return implementationRegistry;
    }

    /**
     * Gets the {@link ParserSupervisor}.
     *
     * @return the {@link ParserSupervisor}
     */
    public ParserSupervisor getParserSupervisor() {
        return parserSupervisor;
    }

    /**
     * Gets the {@link TypeAdapterRegistry}.
     *
     * @return the {@link TypeAdapterRegistry}
     */
    public TypeAdapterRegistry getAdapterRegistry() {
        return adapterRegistry;
    }

    /**
     * Gets the {@link ValidatorRegistry}.
     *
     * @return the {@link ValidatorRegistry}
     */
    public ValidatorRegistry getValidatorRegistry() {
        return validatorRegistry;
    }

    /**
     * Gets the {@link CommandRegistry}.
     *
     * @return the {@link CommandRegistry}
     */
    public CommandRegistry getCommandRegistry() {
        return commandRegistry;
    }

    /**
     * Gets the JDA instance. This can either be {@link JDA} or a {@link ShardManager}. Use {@link #isShardManager()}
     * to distinguish.
     *
     * @return the JDA instance.
     */
    public Object getJda() {
        return jda;
    }

    /**
     * Whether the JDA instance is a {@link ShardManager}.
     *
     * @return {@code true} if the JDA instance is a {@link ShardManager}
     */
    public boolean isShardManager() {
        return isShardManager;
    }

    /**
     * Gets the {@link FilterRegistry}.
     *
     * @return the {@link FilterRegistry}
     */
    public FilterRegistry getFilterRegistry() {
        return filterRegistry;
    }

    /**
     * Gets the {@link Router}.
     *
     * @return the {@link Router}
     * @deprecated use {@link ImplementationRegistry#getRouter()}
     */
    public Router getRouter() {
        return implementationRegistry.getRouter();
    }

    /**
     * Sets the {@link Router}.
     *
     * @param router the {@link Router} to use
     * @deprecated use {@link ImplementationRegistry#setRouter(Router)}
     */
    public void setRouter(@NotNull Router router) {
        implementationRegistry.setRouter(router);
    }

    /**
     * Gets the {@link HelpMessageFactory}.
     *
     * @return the {@link HelpMessageFactory}
     */
    public HelpMessageFactory getHelpMessageFactory() {
        return implementationRegistry.getHelpMessageFactory();
    }

    /**
     * Gets the {@link JDACommands} instance.
     *
     * @return the {@link JDACommands} instance
     */
    public JDACommands getJdaCommands() {
        return jdaCommands;
    }

    /**
     * Gets the {@link DependencyInjector}.
     *
     * @return the {@link DependencyInjector}
     */
    public DependencyInjector getDependencyInjector() {
        return dependencyInjector;
    }
}
