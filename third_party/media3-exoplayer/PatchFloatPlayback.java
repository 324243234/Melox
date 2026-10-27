// SPDX-License-Identifier: Apache-2.0
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Enables the existing float-capable Sonic through the sink's normal timing pipeline. */
public final class PatchFloatPlayback implements Opcodes {
    private static final String SINK = "androidx/media3/exoplayer/audio/DefaultAudioSink";

    private static AbstractInsnNode previousCode(AbstractInsnNode node) {
        do { node = node.getPrevious(); } while (node != null && node.getOpcode() < 0);
        return node;
    }

    private static AbstractInsnNode nextCode(AbstractInsnNode node) {
        do { node = node.getNext(); } while (node != null && node.getOpcode() < 0);
        return node;
    }

    public static void main(String[] args) throws Exception {
        ClassReader reader = new ClassReader(Files.readAllBytes(Path.of(args[0])));
        ClassNode type = new ClassNode(ASM9);
        reader.accept(type, 0);
        if (!type.name.equals(SINK)) throw new IllegalStateException("Unexpected class");
        int changed = 0;
        for (MethodNode method : type.methods) {
            if (method.name.equals("configure") && method.desc.equals(
                    "(Landroidx/media3/exoplayer/audio/AudioSink$AudioSinkConfig;)V")) {
                FieldInsnNode chain = null;
                JumpInsnNode join = null;
                for (AbstractInsnNode node : method.instructions) {
                    if (node instanceof MethodInsnNode call && call.name.equals("shouldUseFloatOutput")) {
                        AbstractInsnNode cursor = nextCode(node);
                        if (cursor.getOpcode() != IFEQ) throw new IllegalStateException("Unexpected float branch");
                        for (cursor = nextCode(cursor); cursor != null; cursor = nextCode(cursor)) {
                            if (cursor instanceof JumpInsnNode jump && jump.getOpcode() == GOTO) {
                                join = jump;
                                break;
                            }
                        }
                    }
                    if (node instanceof FieldInsnNode field && field.owner.equals(SINK)
                            && field.name.equals("audioProcessorChain")) {
                        if (chain != null) throw new IllegalStateException("Ambiguous processor chain");
                        chain = field;
                    }
                }
                if (chain == null || join == null) throw new IllegalStateException("Missing PCM branch");
                AbstractInsnNode start = previousCode(previousCode(chain));
                List<AbstractInsnNode> statement = new ArrayList<>();
                for (AbstractInsnNode node = start; statement.size() < 6; node = nextCode(node)) {
                    statement.add(node);
                }
                int[] expected = {ALOAD, ALOAD, GETFIELD, INVOKEINTERFACE, INVOKEVIRTUAL, POP};
                for (int index = 0; index < expected.length; index++) {
                    if (statement.get(index).getOpcode() != expected[index]) {
                        throw new IllegalStateException("Unexpected processor-chain statement");
                    }
                }
                AbstractInsnNode target = nextCode(join.label);
                if (!(target instanceof org.objectweb.asm.tree.TypeInsnNode allocation)
                        || !allocation.desc.equals("androidx/media3/common/audio/AudioProcessingPipeline")) {
                    throw new IllegalStateException("Unexpected PCM join");
                }
                for (AbstractInsnNode node : statement) method.instructions.remove(node);
                for (AbstractInsnNode node : statement) method.instructions.insertBefore(target, node);
                changed++;
            } else if (method.name.equals("shouldApplyAudioProcessorPlaybackParameters")
                    && method.desc.equals("()Z")) {
                MethodInsnNode floatCheck = null;
                for (AbstractInsnNode node : method.instructions) {
                    if (node instanceof MethodInsnNode call && call.name.equals("shouldUseFloatOutput")) {
                        if (floatCheck != null) throw new IllegalStateException("Ambiguous float guard");
                        floatCheck = call;
                    }
                }
                if (floatCheck == null) throw new IllegalStateException("Missing float guard");
                AbstractInsnNode start = floatCheck;
                for (int index = 0; index < 5; index++) start = previousCode(start);
                int[] expected = {ALOAD, ALOAD, GETFIELD, INVOKESTATIC, GETFIELD, INVOKESPECIAL, IFNE};
                List<AbstractInsnNode> condition = new ArrayList<>();
                for (AbstractInsnNode node = start; condition.size() < expected.length; node = nextCode(node)) {
                    condition.add(node);
                }
                for (int index = 0; index < expected.length; index++) {
                    if (condition.get(index).getOpcode() != expected[index]) {
                        throw new IllegalStateException("Unexpected float guard shape");
                    }
                }
                for (AbstractInsnNode node : condition) method.instructions.remove(node);
                changed++;
            }
        }
        if (changed != 2) throw new IllegalStateException("Expected exactly two changed methods");
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        type.accept(writer);
        Files.write(Path.of(args[1]), writer.toByteArray());
    }
}
