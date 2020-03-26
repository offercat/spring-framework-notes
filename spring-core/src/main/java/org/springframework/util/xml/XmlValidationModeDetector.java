/*
 * Copyright 2002-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.util.xml;

import java.io.BufferedReader;
import java.io.CharConversionException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

/**
 * Detects whether an XML stream is using DTD- or XSD-based validation.
 *
 * @author Rob Harrop
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @since 2.0
 */
public class XmlValidationModeDetector {

	/**
	 * Indicates that the validation should be disabled.
	 */
	public static final int VALIDATION_NONE = 0;

	/**
	 * Indicates that the validation mode should be auto-guessed, since we cannot find
	 * a clear indication (probably choked on some special characters, or the like).
	 */
	public static final int VALIDATION_AUTO = 1;

	/**
	 * Indicates that DTD validation should be used (we found a "DOCTYPE" declaration).
	 */
	public static final int VALIDATION_DTD = 2;

	/**
	 * Indicates that XSD validation should be used (found no "DOCTYPE" declaration).
	 */
	public static final int VALIDATION_XSD = 3;


	/**
	 * The token in a XML document that declares the DTD to use for validation
	 * and thus that DTD validation is being used.
	 */
	private static final String DOCTYPE = "DOCTYPE";

	/**
	 * The token that indicates the start of an XML comment.
	 */
	private static final String START_COMMENT = "<!--";

	/**
	 * The token that indicates the end of an XML comment.
	 */
	private static final String END_COMMENT = "-->";


	/**
	 * Indicates whether or not the current parse position is inside an XML comment.
	 */
	private boolean inComment;


	/**
	 * Detect the validation mode for the XML document in the supplied {@link InputStream}.
	 * 通过指定的输入流检测XML文件的验证模式
	 *
	 * Note that the supplied {@link InputStream} is closed by this method before returning.
	 * 注意，指定的输入流会在这个方法返回前关闭
	 *
	 * @param inputStream the InputStream to parse -> 用于解析的输入流
	 * @throws IOException in case of I/O failure
	 * @see #VALIDATION_DTD
	 * @see #VALIDATION_XSD
	 */
	public int detectValidationMode(InputStream inputStream) throws IOException {
		// 查看文件，看看是否包含 DOCTYPE 标记，这个方法认为，只要包含 DOCTYPE 标记，则认为是 DTD 模式
		// Peek into the file to look for DOCTYPE.
		BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
		try {
			// 设置默认模式为DTD
			boolean isDtdValidated = false;
			// 用字符串来接收每一行的内容
			String content;
			// 开始循环读取每一行的内容
			while ((content = reader.readLine()) != null) {
				// 对这样的是否是注释进行解析，记录
				content = consumeCommentTokens(content);
				// 如果这一行内容在注释中 或 这一行没可用字符
				if (this.inComment || !StringUtils.hasText(content)) {
					continue;
				}
				// 如果包含 DOCTYPE 声明，则设置解析模式为DTD，停止循环
				if (hasDoctype(content)) {
					isDtdValidated = true;
					break;
				}
				// 判断是否读取到XML打开标记，验证模式标记一定会在打开标记标记之前，如果读取到，则直接退出循环
				if (hasOpeningTag(content)) {
					// End of meaningful data...
					break;
				}
			}
			// 根据文本的分析情况，返回适合本输入流的验证模式
			return (isDtdValidated ? VALIDATION_DTD : VALIDATION_XSD);
		}
		catch (CharConversionException ex) {
			// Choked on some character encoding... -> 被某些字符给噎住了
			// Leave the decision up to the caller. -> 把决定权留给调用方
			return VALIDATION_AUTO;
		}
		finally {
			// 别忘记关闭 BufferedReader
			reader.close();
		}
	}


	/**
	 * Does the content contain the DTD DOCTYPE declaration?
	 *
	 * 是否给定的内容中含有DTD模式的 DOCTYPE 声明
	 */
	private boolean hasDoctype(String content) {
		return content.contains(DOCTYPE);
	}

	/**
	 * Does the supplied content contain an XML opening tag.
	 * 提供的内容是否包含XML打开标记。
	 *
	 * If the parse state is currently in an XML comment then this method always returns false.
	 * 如果解析状态当前位于XML注释中，则此方法始终返回false
	 *
	 * It is expected that all comment tokens will have consumed for the supplied content before
	 * passing the remainder to this method.
	 * 在将余数传递给此方法之前，预期所有注释标记都已为提供的内容使用。
	 */
	private boolean hasOpeningTag(String content) {
		if (this.inComment) {
			return false;
		}
		// 获取 < 标记的位置
		int openTagIndex = content.indexOf('<');
		// 含有 < 标记 && 不仅仅含有 < 标记 && < 标记之后是字母
		// 满足以上条件则得出结论 -> 指定内容含有XML打开标记
		return (openTagIndex > -1 && (content.length() > openTagIndex + 1) &&
				Character.isLetter(content.charAt(openTagIndex + 1)));
	}

	/**
	 * Consume all leading and trailing comments in the given String and return
	 * the remaining content, which may be empty since the supplied content might
	 * be all comment data.
	 */
	@Nullable
	private String consumeCommentTokens(String line) {

		//-------------------------------------begin end 都没有的情况------------------------------------
		// 判断是否含有注释起始标记 "<!--"
		int indexOfStartComment = line.indexOf(START_COMMENT);
		// 如果没有起始标记并且也没有结束标记，则将原内容返回。
		if (indexOfStartComment == -1 && !line.contains(END_COMMENT)) {
			return line;
		}

		//----------------------begin end 至少有一个的情况，也就是需要截取有效字段返回------------------------
		// 1、有begin
		// 2、有end
		// 3、begin + end

		//结果字符
		String result = "";
		//截取字符
		String currLine = line;

		// 如果含有起始标记，先用 result 记录有效字符串
		if (indexOfStartComment >= 0) {
			// result 记录有效字符串
			result = line.substring(0, indexOfStartComment);
			// currLine 记录起始标记后的内容
			currLine = line.substring(indexOfStartComment);
		}

		// 首先到这一步，currLine 如果有起始字符，起始字符必然在 currLine 开头
		// 处理currLine，如果截取的字段不为空则循环
		while ((currLine = consume(currLine)) != null) {
			// 如果不在评论中（处理到结束字符） && 处理后的currLine不是以起始标记开头，直接返回结果
			if (!this.inComment && !currLine.trim().startsWith(START_COMMENT)) {
				return result + currLine;
			}
			// 如果还在评论中 || 返回结果以起始标记开头
		}
		//todo 这里似乎有点问题，"xxxxxx<!--" 这种情况可能会被跳过
		return null;
	}

	/**
	 * Consume the next comment token, update the "inComment" flag
	 * and return the remaining content.
	 *
	 * 根据注释标记，寻找注释开始标记或结束标记，并返回截取字段，如果返回null，表示这一样没有注释标记
	 */
	@Nullable
	private String consume(String line) {
		// 判断是否在注释中
		// 如果在，赋值结束标记的位置
		// 如果不在，赋值开始标记的位置
		int index = (this.inComment ? endComment(line) : startComment(line));
		// 如果没找到想要的标记，返回null
		// 如果找到了开始或结束标记，则把标记后的字符串截取下来，返回
		return (index == -1 ? null : line.substring(index));
	}

	/**
	 * Try to consume the {@link #START_COMMENT} token.
	 * @see #commentToken(String, String, boolean)
	 */
	private int startComment(String line) {
		// 如果开始标记在这一行，则把注释标记设置为true，返回开始标记的位置
		// 如果不在，返回 -1
		return commentToken(line, START_COMMENT, true);
	}

	private int endComment(String line) {
		// 如果结束标记在这一行，则把注释标记设置为 false，返回结束标记的位置
		// 如果不在，返回 -1
		return commentToken(line, END_COMMENT, false);
	}

	/**
	 * Try to consume the supplied token against the supplied content and update the
	 * in comment parse state to the supplied value. Returns the index into the content
	 * which is after the token or -1 if the token is not found.
	 */
	private int commentToken(String line, String token, boolean inCommentIfPresent) {
		// 获取指定字符串的起始位置
		int index = line.indexOf(token);
		// 如果含有指定字符串，则将评论标记设置为指定值
		if (index > - 1) {
			this.inComment = inCommentIfPresent;
		}
		// 如果不含指定字符串
		return (index == -1 ? index : index + token.length());
	}

}
