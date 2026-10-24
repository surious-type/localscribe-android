#pragma once

#include <cstddef>
#include <cstdint>
#include <string_view>
#include <vector>

namespace localscribe {

/**
 * Whisper returns ordinary UTF-8, while JNI NewStringUTF requires Modified UTF-8.
 * Decode explicitly to UTF-16 for JNIEnv::NewString. Invalid bytes are replaced
 * instead of aborting the entire Android process in CheckJNI.
 *
 * The input is byte-counted, so this routine also handles embedded NUL bytes.
 */
template <typename CodeUnit = std::uint16_t>
std::vector<CodeUnit> utf8_to_utf16(std::string_view input) {
    static_assert(sizeof(CodeUnit) == 2, "UTF-16 requires 16-bit code units");

    std::vector<CodeUnit> result;
    result.reserve(input.size());
    const auto * bytes = reinterpret_cast<const unsigned char *>(input.data());

    for (std::size_t i = 0; i < input.size();) {
        const unsigned char first = bytes[i];
        std::uint32_t code_point = 0;
        std::size_t width = 1;

        if (first < 0x80) {
            code_point = first;
        } else if (first >= 0xC2 && first <= 0xDF) {
            code_point = first & 0x1F;
            width = 2;
        } else if (first >= 0xE0 && first <= 0xEF) {
            code_point = first & 0x0F;
            width = 3;
        } else if (first >= 0xF0 && first <= 0xF4) {
            code_point = first & 0x07;
            width = 4;
        } else {
            result.push_back(static_cast<CodeUnit>(0xFFFD));
            ++i;
            continue;
        }

        bool valid = width <= input.size() - i;
        for (std::size_t j = 1; valid && j < width; ++j) {
            const unsigned char continuation = bytes[i + j];
            if ((continuation & 0xC0) != 0x80 ||
                (j == 1 && ((first == 0xE0 && continuation < 0xA0) ||
                            (first == 0xED && continuation > 0x9F) ||
                            (first == 0xF0 && continuation < 0x90) ||
                            (first == 0xF4 && continuation > 0x8F)))) {
                valid = false;
                break;
            }
            code_point = (code_point << 6) | (continuation & 0x3F);
        }

        if (!valid) {
            // Advance by one byte so valid text following a bad sequence survives.
            result.push_back(static_cast<CodeUnit>(0xFFFD));
            ++i;
            continue;
        }

        if (code_point <= 0xFFFF) {
            result.push_back(static_cast<CodeUnit>(code_point));
        } else {
            code_point -= 0x10000;
            result.push_back(static_cast<CodeUnit>(0xD800 + (code_point >> 10)));
            result.push_back(static_cast<CodeUnit>(0xDC00 + (code_point & 0x3FF)));
        }
        i += width;
    }
    return result;
}

}  // namespace localscribe
