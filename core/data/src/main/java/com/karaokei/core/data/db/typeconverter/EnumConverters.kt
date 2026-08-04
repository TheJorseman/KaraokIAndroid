package com.karaokei.core.data.db.typeconverter

import androidx.room.TypeConverter
import com.karaokei.core.data.db.entity.ModelTier
import com.karaokei.core.data.db.entity.ModelType
import com.karaokei.core.data.db.entity.ProcessingStage
import com.karaokei.core.data.db.entity.SongStatus

class EnumConverters {
    @TypeConverter fun songStatusToString(value: SongStatus): String = value.name
    @TypeConverter fun stringToSongStatus(value: String): SongStatus = SongStatus.valueOf(value)

    @TypeConverter fun modelTierToString(value: ModelTier): String = value.name
    @TypeConverter fun stringToModelTier(value: String): ModelTier = ModelTier.valueOf(value)

    @TypeConverter fun modelTypeToString(value: ModelType): String = value.name
    @TypeConverter fun stringToModelType(value: String): ModelType = ModelType.valueOf(value)

    @TypeConverter fun stageToString(value: ProcessingStage): String = value.name
    @TypeConverter fun stringToStage(value: String): ProcessingStage = ProcessingStage.valueOf(value)
}
