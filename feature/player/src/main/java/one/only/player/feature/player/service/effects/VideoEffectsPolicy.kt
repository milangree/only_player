package one.only.player.feature.player.service.effects

import one.only.player.core.model.DecoderPriority

// Media3 video effects 对 extension renderer 没有稳定兼容承诺。
internal fun shouldApplyVideoEffects(decoderPriority: DecoderPriority): Boolean = decoderPriority != DecoderPriority.PREFER_APP
