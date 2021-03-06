/*Copyright (c) 2014, Marcelo Augusto Cordeiro, Milstein Lab, University of Toronto

This ImageJ plugin was developed for the Milstein Lab at the University of Toronto, 
with the help of Professor Josh Milstein during the summer of 2014, as part of the
Science Without Borders research opportunity program.
 
This is a version modified by Christophe Leterrier, CRNS-AMU, Marseille (October 2016).

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.*/

import ij.*;
import ij.gui.*;
import ij.plugin.filter.PlugInFilter;
import ij.process.*;

public class Fast_Temporal_Median implements PlugInFilter
{
	private ImageStack stack;
	ImagePlus new_imp;
	
	public int setup(String arg, ImagePlus imp) 
	{
		try
		{
			stack = imp.getStack();
		}catch(Exception e) {}
		new_imp = imp;
		return DOES_16;
	}
	
	public void run(ImageProcessor ip) 
	{
		String help = "<html><font size=\"4\">Copyright (c) 2014, Marcelo Augusto Cordeiro, Milstein Lab, University of Toronto<br>"
					+ "<br>This plugin uses a variant of the Huang algorithm to quickly determine the temporal<br>"
					+ "median  of each pixel across a number of frames within an image stack. The  program<br>"
					+ "then creates a new stack with the running median subtracted.<br>"
					+ "This plugin uses a Forward Window, the program selects a window from frames [i] to<br>"
					+ "[i + Window Size] to find the median.<br>"
					+ "If \"Intensity Normalization\" is activated, the program also accounts for shifts<br>"
					+ "in overall intensity by scaling all pixels according to the mean intensity of each<br>"
					+ "frame.<br>"
                    + "<br>To restrict memory use, you can use the Histogram compression factor.<br>"
                    + "Entering a power of two value (2, 4, 8...) will re-bin the histogram and divide RAM use by the same factor<br>"
                    + "at the expense of a slightly lower precision in median value determination<br>"
                    + "(modification added by Christophe Leterrier, CNRS-AMU, Marseille)<br>"
                    + "<br>To use the plugin, simply select the start frame and end frame of the stack, running<br>"
					+ "window size, and if intensity normalization is to be used.<br>"
					+ "<br>This plugin currently works only with 16-bit grayscale stacks.<br></font></html>";
	
		String name = new_imp.getShortTitle();
	
		new_imp.close();
		
		int size = stack.getSize();
		if (size==1)
		{
			IJ.showMessage("Error","This plugin only works with stacks.");
			return;
		}
		int start, end, window, binfactor;
		String normal;
		boolean ok;
		do
		{
			GenericDialog setup = new GenericDialog("Settings");
			setup.addMessage("Temporal Median Filter");
			setup.addNumericField("Start Frame",1,0);
			setup.addNumericField("End Frame",size,0);
			setup.addMessage("");
			setup.addNumericField("Window Size",27,0);
            setup.addMessage("");
            setup.addNumericField("Histogram compresssion factor",1,0);
			setup.addMessage("");
			setup.addRadioButtonGroup("Intensity Normalization", new String[] {"Yes", "No"}, 1, 2, "No");
			setup.addHelp(help);
			setup.showDialog();
			if (setup.wasCanceled())
				return;
			ok = true;
			normal = new String(setup.getNextRadioButton());
			start = (int)(setup.getNextNumber());
			end = (int)(setup.getNextNumber());
			window = (int)(setup.getNextNumber());
            binfactor = (int)(setup.getNextNumber());
			if ((start < 1) || (start > (size-window)))
			{
				ok = false;
				IJ.showMessage("Error","The start frame must be between 1 and " + String.valueOf(size-window));
			}
			if ((end > size) || (end < window))
			{
				ok = false;
				IJ.showMessage("Error","The end frame must be between " + String.valueOf(window) + " and " + String.valueOf(size));
			}
			if ((window < 2) || (window > size))
			{
				ok = false;
				IJ.showMessage("Error","The window size must be between 2 and " + String.valueOf(size));
			}
		}while(!ok);
		
		size = end;
		
		IJ.showStatus("Allocating memory...");
		
		ImageStack sub = new ImageStack(stack.getWidth(),stack.getHeight()); //ImageStack to save the filtered images
	
		int dimension = stack.getWidth()*stack.getHeight(); //ImageJ saves the pixels of the image in an unidimensional array of size width*height
        int bitdepth = 65536; // color depth of image
        int colors = (int) (bitdepth / binfactor); // bit depth for the re-binned histogram (uses less RAM)
        // IJ.showStatus("colors=" + colors);
        short[] pixelsraw = new short[dimension];
        short[] pixelsraw2 = new short[dimension]; //Arrays to save the raw value of pixels that are being processed
        int[] pixels = new int[dimension];
        int[] pixels2 = new int[dimension]; //Arrays to save the rebinned values of pixels that are being processed
		short[] median = new short[dimension]; //Array to save the median pixels
		byte[] aux = new byte[dimension]; //Marks the position of each median pixel in the column of the histogram, starting with 1 
		byte[][] hist = new byte[dimension][colors]; //Gray-level histogram
		
		System.gc();
		
		if (normal.equals("No"))
		{
			for (int k=start; k<=(size-window); k++) //Each passing creates one median frame
			{
				IJ.showStatus("Frame " + String.valueOf(k) + "/" + String.valueOf(size));
				IJ.showProgress(k,size);
				
				//median = median.clone(); //Cloning the median, or else the changes would overlap the previous median
				
				if (k==start) //Building the first histogram
				{
					for (int i=1; i<=window; i++) //For each frame inside the window
					{
						pixelsraw = (short[])(stack.getPixels(i+k-1)); //Save all the pixels of the frame "i+k-1" in "pixels" (starting with 1)
						for (int j=0; j<dimension; j++) //For each pixel in this frame
                        {
                            pixels[j] = (int) pixelsraw[j] / binfactor; // rebin the pixel value
                            hist[j][pixels[j]]++; //Add it to the histogram
                        }
					}
					for (int i=0; i<dimension; i++) //Calculating the median
					{	
						short count=0, j=-1;
						while(count<(window/2)) //Counting the histogram, until it reaches the median
						{
							j++;
							count += hist[i][j];
						}
						aux[i] = (byte)(count - (int)(Math.ceil(window/2)) + 1);
						median[i] = j;
					}
				}
				else
				{
					pixelsraw = (short[])(stack.getPixels(k-1)); //Old pixels, remove them from the histogram
					pixelsraw2 = (short[])(stack.getPixels(k+window-1)); //New pixels, add them to the histogram
					for (int i=0; i<dimension; i++) //Calculating the new median
					{
						pixels[i] = (int) pixelsraw[i] / binfactor; // rebin the pixel value
                        pixels2[i] = (int) pixelsraw2[i] / binfactor; // rebin the pixel value
                        hist[i][pixels[i]]--; //Removing old pixel
						hist[i][pixels2[i]]++; //Adding new pixel
						if (!(((pixels[i]>median[i]) &&
						   (pixels2[i]>median[i])) ||
						   ((pixels[i]<median[i]) &&
						   (pixels2[i]<median[i])) ||
						   ((pixels[i]==median[i]) &&
						   (pixels2[i]==median[i]))))
						//Add and remove the same pixel, or pixels from the same side, the median doesn't change
						{
							int j=median[i];
							if ((pixels2[i]>median[i]) && (pixels[i]<median[i])) //The median goes right
							{
								if (hist[i][median[i]] == aux[i]) //The previous median was the last pixel of its column in the histogram, so it changes
								{
									j++;
									while (hist[i][j] == 0) //Searching for the next pixel
										j++;
									median[i] = (short)(j);
									aux[i] = 1; //The median is the first pixel of its column
								}
								else
									aux[i]++; //The previous median wasn't the last pixel of its column, so it doesn't change, just need to mark its new position
							}
							else if ((pixels[i]>median[i]) && (pixels2[i]<median[i])) //The median goes left
							{
								if (aux[i] == 1) //The previous median was the first pixel of its column in the histogram, so it changes
								{
									j--;
									while (hist[i][j] == 0) //Searching for the next pixel
										j--;
									median[i] = (short)(j);
									aux[i] = hist[i][j]; //The median is the last pixel of its column
								}
								else
									aux[i]--; //The previous median wasn't the first pixel of its column, so it doesn't change, just need to mark its new position
							}
							else if (pixels2[i]==median[i]) //new pixel = last median
							{
								if (pixels[i]<median[i]) //old pixel < last median, the median goes right
									aux[i]++; //There is at least one pixel above the last median (the one that was just added), so the median doesn't change, just need to mark its new position
								//else, absolutely nothing changes
							}
							else //pixels[i]==median[i], old pixel = last median
							{
								if (pixels2[i]>median[i]) //new pixel > last median, the median goes right
								{
									if (aux[i] == (hist[i][median[i]]+1)) //The previous median was the last pixel of its column, so it changes
									{
										j++;
										while (hist[i][j] == 0) //Searching for the next pixel
											j++;
										median[i] = (short)(j); 
										aux[i] = 1; //The median is the first pixel of its column
									}
									//else, absolutely nothing changes
								}
								else //pixels2[i]<median[i], new pixel < last median, the median goes left
								{
									if (aux[i] == 1) //The previous median was the first pixel of its column in the histogram, so it changes
									{
										j--;
										while (hist[i][j] == 0) //Searching for the next pixel
											j--;
										median[i] = (short)(j);
										aux[i] = hist[i][j]; //The median is the last pixel of its column
									}
									else
										aux[i]--; //The previous median wasn't the first pixel of its column, so it doesn't change, just need to mark its new position
								}
							}
						}
					}
				}
				
				//Subtracting the median
				pixelsraw = (short[])(stack.getPixels(k));
				pixelsraw = pixelsraw.clone();
				for (int j=0; j<dimension; j++)
				{
                    pixelsraw[j] -= median[j] * binfactor;
					if (pixelsraw[j] < 0)
						pixelsraw[j] = 0;
				}
				//ip.setPixels(pixelsraw);
				//stack.addSlice("",pixelsraw);
				//stack.deleteSlice(k);
				sub.addSlice("",pixelsraw); //Add the frame to the stack
				
				if ((k%1000) == 0)
					System.gc(); //Calls the Garbage Collector every 1000 frames
			}
		}
		else //Using intensity normalization
		{
			int[] mean = new int[size];
			for (int i=start; i<size; i++) //Calculating the mean of all frames
			{
				pixelsraw = (short[])(stack.getPixels(i));
				for (int j=0; j<dimension;j++)
					mean[i-1] += pixelsraw[j];
				mean[i-1] /= dimension;
			}
			
			for (int k=start; k<=(size-window); k++) //Each passing creates one median frame
			{
				IJ.showStatus("Frame " + String.valueOf(k) + "/" + String.valueOf(size));
				IJ.showProgress(k,size);
				
				//median = median.clone(); //Cloning the median, or else the changes would overlap the previous median
				
				if (k==start) //Building the first histogram
				{
					for (int i=1; i<=window; i++) //For each frame inside the window
					{
						pixelsraw = (short[])(stack.getPixels(i+k-1)); //Save all the pixels of the frame "i+k-1" in "pixels" (starting with 1)
						
						for (int j=0; j<dimension; j++) //For each pixel in this frame
						{
							pixels[j] = (int) pixelsraw[j] / binfactor; // rebin the pixel value
                            hist[j][(int)(((float)pixels[j]/(float)mean[i+k-2])*1000)]++; //Add it to the histogram, already normalized
						}
					}
					for (int i=0; i<dimension; i++) //Calculating the median
					{
						short count=0, j=-1;
						while(count<(window/2)) //Counting the histogram, until it reaches the median
						{
							j++;
							count += hist[i][j];
						}
						aux[i] = (byte)(count - (int)(Math.ceil(window/2)) + 1);
						median[i] = j;
					}
				}
				else
				{
					pixelsraw = (short[])(stack.getPixels(k-1)); //Old pixels, remove them from the histogram
					pixelsraw2 = (short[])(stack.getPixels(k+window-1)); //New pixels, add them to the histogram
					for (int i=0; i<dimension; i++) //Calculating the new median
					{
						pixels[i] = (int) pixelsraw[i] / binfactor; // rebin the pixel value
                        pixels2[i] = (int) pixelsraw2[i] / binfactor; // rebin the pixel value
                        hist[i][(int)(((float)pixels[i]/(float)mean[k-2])*1000)]--; //Removing old pixel (normalized)
						hist[i][(int)(((float)pixels2[i]/(float)mean[k+window-2])*1000)]++; //Adding new pixel (normalized)
						if (!((((int)(((float)pixels[i]/(float)mean[k-2])*1000)>median[i]) &&
						   ((int)(((float)pixels2[i]/(float)mean[k+window-2])*1000)>median[i])) ||
						   (((int)(((float)pixels[i]/(float)mean[k-2])*1000)<median[i]) &&
						   ((int)(((float)pixels2[i]/(float)mean[k+window-2])*1000)<median[i])) ||
						   (((int)(((float)pixels[i]/(float)mean[k-2])*1000)==median[i]) &&
						   ((int)(((float)pixels2[i]/(float)mean[k+window-2])*1000)==median[i]))))
						   //Add and remove the same pixel, or pixels from the same side, the median doesn't change
						{
							int j=median[i];
							if (((int)(((float)pixels2[i]/(float)mean[k+window-2])*1000)>median[i]) && ((int)(((float)pixels[i]/(float)mean[k-2])*1000)<median[i])) //The median goes right
							{
								if (hist[i][median[i]] == aux[i]) //The previous median was the last pixel of its column in the histogram, so it changes
								{
									j++;
									while (hist[i][j] == 0) //Searching for the next pixel
										j++;
									median[i] = (short)(j);
									aux[i] = 1; //The median is the first pixel of its column
								}
								else
									aux[i]++; //The previous median wasn't the last pixel of its column, so it doesn't change, just need to mark its new position
							}
							else if (((int)(((float)pixels[i]/(float)mean[k-2])*1000)>median[i]) && ((int)(((float)pixels2[i]/(float)mean[k+window-2])*1000)<median[i])) //The median goes left
							{
								if (aux[i] == 1) //The previous median was the first pixel of its column in the histogram, so it changes
								{
									j--;
									while (hist[i][j] == 0) //Searching for the next pixel
										j--;
									median[i] = (short)(j);
									aux[i] = hist[i][j]; //The median is the last pixel of its column
								}
								else
									aux[i]--; //The previous median wasn't the first pixel of its column, so it doesn't change, just need to mark its new position
							}
							else if ((int)(((float)pixels2[i]/(float)mean[k+window-2])*1000)==median[i]) //new pixel = last median
							{
								if ((int)(((float)pixels[i]/(float)mean[k-2])*1000)<median[i]) //old pixel < last median, the median goes right
									aux[i]++; //There is at least one pixel above the last median (the one that was just added), so the median doesn't change, just need to mark its new position
								//else, absolutely nothing changes
							}
							else //pixels[i]==median[i], old pixel = last median
							{
								if ((int)(((float)pixels2[i]/(float)mean[k+window-2])*1000)>median[i]) //new pixel > last median, the median goes right
								{
									if (aux[i] == (hist[i][median[i]]+1)) //The previous median was the last pixel of its column, so it changes
									{
										j++;
										while (hist[i][j] == 0) //Searching for the next pixel
											j++;
										median[i] = (short)(j); 
										aux[i] = 1; //The median is the first pixel of its column
									}
									//else, absolutely nothing changes
								}
								else //pixels2[i]<median[i], new pixel < last median, the median goes left
								{
									if (aux[i] == 1) //The previous median was the first pixel of its column in the histogram, so it changes
									{
										j--;
										while (hist[i][j] == 0) //Searching for the next pixel
											j--;
										median[i] = (short)(j);
										aux[i] = hist[i][j]; //The median is the last pixel of its column
									}
									else
										aux[i]--; //The previous median wasn't the first pixel of its column, so it doesn't change, just need to mark its new position
								}
							}
						}
					}
				}
				
				//Subtracting the median
				pixelsraw = (short[])(stack.getPixels(k));
				pixelsraw = pixelsraw.clone();
				for (int j=0; j<dimension; j++)
				{
					pixelsraw[j] -= (median[j] * binfactor * ((float)mean[k-1]/(float)1000));
					if (pixelsraw[j] < 0)
						pixelsraw[j] = 0;
				}
				sub.addSlice("",pixelsraw); //Add the frame to the stack
				
				if ((k%1000) == 0)
					System.gc();  //Calls the Garbage Collector every 1000 frames
			}
		}
		
		new ImagePlus("Med_" + name, sub).show(); //Displaying the final stack
	}
}
