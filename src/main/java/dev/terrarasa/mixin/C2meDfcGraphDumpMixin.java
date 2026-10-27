package dev.terrarasa.mixin;

import dev.terrarasa.SeedOverride;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * C2ME's density-function compiler writes diagnostic DOT graphs during level construction.
 * Those graphs are not used by the compiled functions; avoid generating them for staged worlds,
 * where the work happens synchronously as part of run preparation.
 */
@Pseudo
@Mixin(targets = "com.ishland.c2me.opts.dfc.common.gen.jvm.BytecodeGen", remap = false)
abstract class C2meDfcGraphDumpMixin {
    @WrapOperation(
            method = "finalizeCompilation",
            at = @At(value = "INVOKE", target = "Lcom/ishland/c2me/opts/dfc/common/gen/GenDumper;dumpDot(Ljava/lang/String;Ljava/nio/file/Path;Ljava/util/Map;Ljava/util/function/Function;)V", remap = false),
            require = 0)
    private static void terrarasa$skipStagedGraphDump(String className, Path compiledClass,
            Map<?, ?> graphs, Function<?, ?> methodSource, Operation<Void> original) {
        if (!SeedOverride.isConstructingStagedLevel()) {
            original.call(className, compiledClass, graphs, methodSource);
        }
    }
}
