为当前页生成 item：id、title、bullets（每条为 text 和 anchors），可选 speaker_notes 和 figure。标题必须等于规划 heading。
根据 contract.figure_required 选择素材中已有的 frame_ids。规划时每个单元声明 frame_ids，必须属于该单元 anchors 引用的素材。写作与修复只使用规划中的帧，图注引用包含该帧的句子。禁止虚构帧编号或从外部下载图片。
