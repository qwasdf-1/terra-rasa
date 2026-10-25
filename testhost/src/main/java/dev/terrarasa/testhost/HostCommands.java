package dev.terrarasa.testhost;

import java.util.function.Supplier;
import java.util.stream.Stream;

import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.terrarasa.FaultInjection;
import dev.terrarasa.Json;
import dev.terrarasa.LifecycleException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * {@code /terrarasa status | prepare [seed [radius]] | activate | gc | fail <point> | poi-before-retire <bool> | player <target>}. Replies
 * are compact JSON for the test harness.
 */
final class HostCommands {
    private HostCommands() {
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("terrarasa")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("status").executes(ctx -> json(ctx, () -> TestHost.host().status())))
                .then(Commands.literal("prepare")
                        .executes(ctx -> prepare(ctx, null, null))
                        .then(Commands.argument("seed", LongArgumentType.longArg())
                                .executes(ctx -> prepare(ctx, LongArgumentType.getLong(ctx, "seed"), null))
                                .then(Commands.argument("radius", IntegerArgumentType.integer(0, 32))
                                        .executes(ctx -> prepare(ctx, LongArgumentType.getLong(ctx, "seed"),
                                                IntegerArgumentType.getInteger(ctx, "radius"))))))
                .then(Commands.literal("activate").executes(ctx -> {
                    String reply = TestHost.host().requestActivation() ? "activated" : "activation queued";
                    ctx.getSource().sendSuccess(() -> Component.literal(reply), false);
                    return 1;
                }))
                .then(Commands.literal("gc").executes(ctx -> json(ctx, () -> {
                    // Leak check: retired levels are only meaningfully unreachable after a collection.
                    System.gc();
                    JsonObject json = new JsonObject();
                    json.addProperty("retiredLevelsReachable", TestHost.host().reachableRetiredLevels());
                    return json;
                })))
                .then(Commands.literal("fail")
                        .then(Commands.argument("point", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                        Stream.concat(FaultInjection.POINTS.stream(), Stream.of("none")), builder))
                                .executes(HostCommands::armFault)))
                .then(Commands.literal("poi-before-retire")
                        .then(Commands.argument("enabled", BoolArgumentType.bool()).executes(ctx -> {
                            TestHost.host().poiChangeBeforeRetire = BoolArgumentType.getBool(ctx, "enabled");
                            ctx.getSource().sendSuccess(() -> Component.literal("ok"), false);
                            return 1;
                        })))
                .then(Commands.literal("player")
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(ctx -> {
                                    var player = EntityArgument.getPlayer(ctx, "target");
                                    return json(ctx, () -> Host.describePlayer(player));
                                }))));
    }

    private static int prepare(CommandContext<CommandSourceStack> ctx, @Nullable Long seed, @Nullable Integer radius) {
        try {
            TestHost.host().prepare(seed, radius);
        } catch (LifecycleException e) {
            ctx.getSource().sendFailure(Component.literal("Preparation failed: " + e.getMessage()));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("preparing"), false);
        return 1;
    }

    private static int armFault(CommandContext<CommandSourceStack> ctx) {
        String point = StringArgumentType.getString(ctx, "point");
        try {
            FaultInjection.arm(point.equals("none") ? null : point);
        } catch (LifecycleException e) {
            ctx.getSource().sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("armed " + point), false);
        return 1;
    }

    private static int json(CommandContext<CommandSourceStack> ctx, Supplier<JsonObject> body) {
        String text = Json.COMPACT.toJson(body.get());
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }
}
